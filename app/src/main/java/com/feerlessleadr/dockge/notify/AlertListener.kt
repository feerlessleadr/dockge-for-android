package com.feerlessleadr.dockge.notify

import android.content.Context
import com.feerlessleadr.dockge.core.TokenVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Persists one alert source's settings and how far the app has read, so a restart resumes where it stopped. */
interface SourceStore {
    fun load(): SourceSettings
    fun save(settings: SourceSettings)

    /** Id of the last message that was handled, or null before the first one. */
    fun cursor(): String?
    fun saveCursor(id: String)
}

class PrefsSourceStore(context: Context, name: String) : SourceStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun load() = SourceSettings(
        enabled = prefs.getBoolean("enabled", false),
        serverUrl = prefs.getString("server", "") ?: "",
        topic = prefs.getString("topic", "") ?: "",
    )

    override fun save(settings: SourceSettings) {
        val changed = settings.serverUrl != load().serverUrl || settings.topic != load().topic
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("server", settings.serverUrl)
            .putString("topic", settings.topic)
            .apply { if (changed) remove("cursor") } // another topic or server means another message history
            .commit()
    }

    override fun cursor(): String? = prefs.getString("cursor", null)

    override fun saveCursor(id: String) {
        prefs.edit().putString("cursor", id).apply()
    }
}

sealed interface SourceState {
    data object Off : SourceState
    data object Connecting : SourceState
    data class Connected(val since: Long) : SourceState
    data class Failed(val message: String) : SourceState
}

/**
 * Keeps one subscription to one alert source open while it is enabled and hands every new message to [onMessage]. Reconnects with a
 * growing delay (1 s up to 60 s). Resumes with the id of the last handled message, so nothing is lost across
 * reconnects and restarts; on the very first start it begins "now" instead of replaying the topic's history.
 */
class AlertListener(
    private val store: SourceStore,
    private val vault: TokenVault,
    private val client: AlertSource,
    private val tokenKey: String,
    private val scope: CoroutineScope,
    private val needsTopic: Boolean = true,
    /** True when [new] comes after [old]; keeps the cursor from moving back when two paths deliver out of order. */
    private val isNewer: (old: String, new: String) -> Boolean = { _, _ -> true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val onMessage: (AlertMessage) -> Unit,
) {
    private val _state = MutableStateFlow<SourceState>(SourceState.Off)
    val state: StateFlow<SourceState> = _state.asStateFlow()
    private var job: Job? = null
    private val seen = ArrayDeque<String>()

    /** Starts, restarts or stops the subscription to match the saved settings. Call after every settings change. */
    fun apply() {
        job?.cancel()
        job = null
        val settings = store.load()
        if (!settings.enabled || settings.serverUrl.isBlank() || (needsTopic && settings.topic.isBlank())) {
            _state.value = SourceState.Off
            return
        }
        _state.value = SourceState.Connecting
        job = scope.launch { run(settings) }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = SourceState.Off
    }

    private suspend fun run(settings: SourceSettings) {
        var wait = MIN_WAIT_MS
        while (true) {
            val since = store.cursor() ?: (clock() / 1000).toString()
            try {
                client.stream(settings.serverUrl, settings.topic, vault.get(tokenKey), since).collect { event ->
                    when (event) {
                        AlertEvent.Open -> {
                            wait = MIN_WAIT_MS
                            _state.value = SourceState.Connected(clock())
                        }
                        is AlertEvent.Message -> handle(event.message)
                    }
                }
                _state.value = SourceState.Failed("The server closed the connection. Reconnecting.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SourceState.Failed(e.message ?: "Could not reach the server.")
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(MAX_WAIT_MS)
            _state.value = SourceState.Connecting
        }
    }

    private fun handle(message: AlertMessage) {
        // The server can repeat a message after a reconnect when it no longer knows the cursor id.
        if (message.id in seen) return
        seen.addLast(message.id)
        if (seen.size > SEEN_LIMIT) seen.removeFirst()
        val old = store.cursor()
        if (old == null || isNewer(old, message.id)) store.saveCursor(message.id)
        onMessage(message)
    }

    companion object {
        const val MIN_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 60_000L
        private const val SEEN_LIMIT = 200
    }
}
