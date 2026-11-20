package com.feerlessleadr.dockge.notify

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.feerlessleadr.dockge.core.TokenVault

/** What the Alerts screen talks to for one alert source: saves its settings and token and restarts its listener. */
class SourceController(
    val label: String,
    private val store: SourceStore,
    private val vault: TokenVault,
    private val tokenKey: String,
    val listener: AlertListener,
    private val allowCleartext: Boolean,
    /** ntfy has topics; Gotify does not. */
    val usesTopic: Boolean,
    /** Gotify cannot listen without its client token. */
    private val tokenRequired: Boolean,
    /** Posts a test message to the saved server, or null when the source has no test button. */
    private val publish: (suspend (SourceSettings, token: String?) -> Result<Unit>)?,
    private val onChanged: () -> Unit,
) {
    val state = listener.state
    val canTest: Boolean get() = publish != null

    fun settings(): SourceSettings = store.load()

    fun hasToken(): Boolean = vault.get(tokenKey) != null

    /**
     * Validates and saves. A blank [token] keeps the saved token unless [clearToken] is set. The token is only ever
     * stored in the vault, never in the settings and never in a log.
     */
    fun save(serverUrl: String, topic: String, enabled: Boolean, token: String, clearToken: Boolean): Result<Unit> {
        val checked = NtfyValidation.check(serverUrl, topic, enabled, allowCleartext, requireTopic = usesTopic)
            .getOrElse { return Result.failure(it) }
        val willHaveToken = !clearToken && (token.isNotBlank() || hasToken())
        if (tokenRequired && enabled && !willHaveToken) {
            return Result.failure(IllegalArgumentException("Enter the client token (Gotify: Clients, create one)."))
        }
        store.save(checked)
        when {
            clearToken -> vault.remove(tokenKey)
            token.isNotBlank() -> vault.put(tokenKey, token.trim())
        }
        listener.apply()
        onChanged()
        return Result.success(Unit)
    }

    suspend fun sendTest(): Result<Unit> {
        val send = publish ?: return Result.failure(IllegalStateException("No test message for this source."))
        val s = store.load()
        if (s.serverUrl.isBlank() || (usesTopic && s.topic.isBlank())) {
            return Result.failure(IllegalStateException("Save the server first."))
        }
        return send(s, vault.get(tokenKey))
    }

    val enabled: Boolean get() = store.load().enabled
}

/** Both alert sources and the one foreground service that keeps them alive. */
class AlertSources(private val app: Application) {
    lateinit var ntfy: SourceController
    lateinit var gotify: SourceController

    private val all get() = listOf(ntfy, gotify)

    fun anyEnabled(): Boolean = all.any { it.enabled }

    /** Starts the listeners that are enabled (stops the others). */
    fun applyAll() = all.forEach { it.listener.apply() }

    /** Starts the foreground service when any source is enabled, stops it when none is. */
    fun updateService() {
        if (anyEnabled()) startService() else app.stopService(Intent(app, AlertService::class.java))
    }

    fun startServiceIfEnabled() {
        if (anyEnabled()) startService()
    }

    private fun startService() {
        // Android refuses to start a foreground service from the background in some cases. The listeners still run
        // while the process lives, and the next time the app opens it starts the service again.
        try {
            ContextCompat.startForegroundService(app, Intent(app, AlertService::class.java))
        } catch (e: Exception) {
            // not allowed right now
        }
    }
}
