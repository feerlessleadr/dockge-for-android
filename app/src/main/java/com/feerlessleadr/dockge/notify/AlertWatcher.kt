package com.feerlessleadr.dockge.notify

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.feerlessleadr.dockge.api.ServerUpdates
import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.data.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the last look at one instance's monitoring feed went. */
sealed interface PollStatus {
    data object Waiting : PollStatus
    data class Ok(val at: Long) : PollStatus
    data class Failed(val message: String) : PollStatus
}

/**
 * Watches the saved instances directly, with no notification server: image updates come from the update status the
 * app already keeps, everything else from each instance's monitoring feed, polled while the instance is connected.
 * Runs while alerts are enabled and the process is alive; [AlertService] keeps the process alive.
 */
class AlertWatcher(
    private val instances: StateFlow<List<Instance>>,
    private val states: StateFlow<Map<String, ConnectionState>>,
    private val updates: StateFlow<Map<String, Map<String, ServerUpdates>>>,
    private val stacksOf: (instanceId: String) -> List<Pair<String, String>>,
    private val token: (instanceId: String) -> String?,
    private val source: MonitoringSource,
    private val engine: AlertEngine,
    private val settingsStore: AlertSettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
    private val tickMs: Long = TICK_MS,
) {
    private val _status = MutableStateFlow<Map<String, PollStatus>>(emptyMap())

    /** Per instance id; absent until the first look. */
    val status: StateFlow<Map<String, PollStatus>> = _status.asStateFlow()
    private var jobs = emptyList<Job>()
    private val lastPoll = mutableMapOf<String, Long>()

    fun settings(): AlertSettings = settingsStore.load()

    /** Starts or stops watching to match the saved settings. Call after every settings change and at app start. */
    fun apply() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        if (!settingsStore.load().enabled) return
        lastPoll.clear()
        jobs = listOf(
            scope.launch { updates.collect { all -> all.forEach { (id, byServer) -> instance(id)?.let { engine.onUpdates(it, byServer) } } } },
            scope.launch {
                var known = emptySet<String>()
                instances.collect { list ->
                    val now = list.map { it.id }.toSet()
                    (known - now).forEach { engine.forget(it); lastPoll.remove(it); _status.update { m -> m - it } }
                    known = now
                }
            },
            scope.launch {
                while (true) {
                    for (i in instances.value) {
                        if (states.value[i.id] != ConnectionState.Connected) continue
                        if (clock() - (lastPoll[i.id] ?: Long.MIN_VALUE / 2) < pollMs) continue
                        poll(i)
                    }
                    delay(tickMs)
                }
            },
        )
    }

    private fun instance(id: String) = instances.value.firstOrNull { it.id == id }

    private suspend fun poll(i: Instance) {
        lastPoll[i.id] = clock()
        val saved = token(i.id)
        if (saved == null) {
            setStatus(i.id, PollStatus.Failed("Not signed in."))
            return
        }
        try {
            val overview = source.overview(i.baseUrl, saved)
            engine.onOverview(i, overview, stacksOf(i.id))
            setStatus(i.id, PollStatus.Ok(clock()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(i.id, PollStatus.Failed(e.message ?: "Could not read the monitoring feed."))
        }
    }

    private fun setStatus(id: String, s: PollStatus) = _status.update { it + (id to s) }

    companion object {
        const val POLL_MS = 60_000L
        const val TICK_MS = 5_000L
    }
}

/** What the Alerts screen and the service talk to: saves the settings, restarts the watcher, runs the service. */
class AlertController(private val app: Application, val watcher: AlertWatcher, private val store: AlertSettingsStore) {
    fun settings() = store.load()

    fun save(settings: AlertSettings) {
        store.save(settings)
        watcher.apply()
        if (settings.enabled) startService() else app.stopService(Intent(app, AlertService::class.java))
    }

    fun startServiceIfEnabled() {
        if (store.load().enabled) startService()
    }

    private fun startService() {
        // Android refuses to start a foreground service from the background in some cases. The watcher still runs
        // while the process lives, and the next time the app opens it starts the service again.
        try {
            ContextCompat.startForegroundService(app, Intent(app, AlertService::class.java))
        } catch (e: Exception) {
            // not allowed right now
        }
    }
}
