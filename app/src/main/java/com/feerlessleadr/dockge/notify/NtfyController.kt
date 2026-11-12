package com.feerlessleadr.dockge.notify

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.feerlessleadr.dockge.core.TokenVault

/** What the settings screen talks to: saves the ntfy settings, runs the listener and the foreground service. */
class NtfyController(
    private val app: Application,
    private val store: NtfyStore,
    private val vault: TokenVault,
    val listener: NtfyListener,
    private val client: NtfyClient,
    private val allowCleartext: Boolean,
) {
    val state = listener.state

    fun settings(): NtfySettings = store.load()

    fun hasToken(): Boolean = vault.get(NtfyListener.TOKEN_KEY) != null

    /**
     * Validates and saves. A blank [token] keeps the saved token unless [clearToken] is set. The token is only ever
     * stored in the vault, never in the settings and never in a log.
     */
    fun save(serverUrl: String, topic: String, enabled: Boolean, token: String, clearToken: Boolean): Result<Unit> {
        val checked = NtfyValidation.check(serverUrl, topic, enabled, allowCleartext).getOrElse { return Result.failure(it) }
        store.save(checked)
        when {
            clearToken -> vault.remove(NtfyListener.TOKEN_KEY)
            token.isNotBlank() -> vault.put(NtfyListener.TOKEN_KEY, token.trim())
        }
        listener.apply()
        if (checked.enabled) startService() else app.stopService(Intent(app, NtfyService::class.java))
        return Result.success(Unit)
    }

    /** Posts one message to the saved topic, so the owner can check the whole path without Dockge. */
    suspend fun sendTest(): Result<Unit> {
        val s = store.load()
        if (!s.usable) return Result.failure(IllegalStateException("Save the server and topic first."))
        return client.publish(s.serverUrl, s.topic, vault.get(NtfyListener.TOKEN_KEY), "Dockge test", "This message came from the Dockge app.")
    }

    fun startServiceIfEnabled() {
        if (store.load().enabled) startService()
    }

    private fun startService() {
        // Android refuses to start a foreground service from the background in some cases. The listener still runs
        // while the process lives, and the next time the app opens it starts the service again.
        try {
            ContextCompat.startForegroundService(app, Intent(app, NtfyService::class.java))
        } catch (e: Exception) {
            // not allowed right now
        }
    }
}
