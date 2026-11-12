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

/** Persists the ntfy settings and how far the app has read, so a restart resumes where it stopped. */
interface NtfyStore {
    fun load(): NtfySettings
    fun save(settings: NtfySettings)

    /** Id of the last message that was handled, or null before the first one. */
    fun cursor(): String?
    fun saveCursor(id: String)
}

class PrefsNtfyStore(context: Context) : NtfyStore {
    private val prefs = context.applicationContext.getSharedPreferences("ntfy", Context.MODE_PRIVATE)

    override fun load() = NtfySettings(
        enabled = prefs.getBoolean("enabled", false),
        serverUrl = prefs.getString("server", "") ?: "",
        topic = prefs.getString("topic", "") ?: "",
    )

    override fun save(settings: NtfySettings) {
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

sealed interface NtfyState {
    data object Off : NtfyState
    data object Connecting : NtfyState
    data class Connected(val since: Long) : NtfyState
    data class Failed(val message: String) : NtfyState
}

/**
 * Keeps one subscription open while ntfy is enabled and hands every new message to [onMessage]. Reconnects with a
 * growing delay (1 s up to 60 s). Resumes with the id of the last handled message, so nothing is lost across
 * reconnects and restarts; on the very first start it begins "now" instead of replaying the topic's history.
 */
class NtfyListener(
    private val store: NtfyStore,
    private val vault: TokenVault,
    private val client: NtfySource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onMessage: (NtfyMessage) -> Unit,
) {
    private val _state = MutableStateFlow<NtfyState>(NtfyState.Off)
    val state: StateFlow<NtfyState> = _state.asStateFlow()
    private var job: Job? = null
    private val seen = ArrayDeque<String>()

    /** Starts, restarts or stops the subscription to match the saved settings. Call after every settings change. */
    fun apply() {
        job?.cancel()
        job = null
        val settings = store.load()
        if (!settings.enabled || !settings.usable) {
            _state.value = NtfyState.Off
            return
        }
        _state.value = NtfyState.Connecting
        job = scope.launch { run(settings) }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = NtfyState.Off
    }

    private suspend fun run(settings: NtfySettings) {
        var wait = MIN_WAIT_MS
        while (true) {
            val since = store.cursor() ?: (clock() / 1000).toString()
            try {
                client.stream(settings.serverUrl, settings.topic, vault.get(TOKEN_KEY), since).collect { event ->
                    when (event) {
                        NtfyEvent.Open -> {
                            wait = MIN_WAIT_MS
                            _state.value = NtfyState.Connected(clock())
                        }
                        is NtfyEvent.Message -> handle(event.message)
                    }
                }
                _state.value = NtfyState.Failed("The ntfy server closed the connection. Reconnecting.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = NtfyState.Failed(e.message ?: "Could not reach the ntfy server.")
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(MAX_WAIT_MS)
            _state.value = NtfyState.Connecting
        }
    }

    private fun handle(message: NtfyMessage) {
        store.saveCursor(message.id)
        // The server can repeat a message after a reconnect when it no longer knows the cursor id.
        if (message.id in seen) return
        seen.addLast(message.id)
        if (seen.size > SEEN_LIMIT) seen.removeFirst()
        onMessage(message)
    }

    companion object {
        /** The key of the ntfy access token in the [TokenVault] (instance ids are UUIDs, so this cannot clash). */
        const val TOKEN_KEY = "ntfy-access-token"
        const val MIN_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 60_000L
        private const val SEEN_LIMIT = 200
    }
}
