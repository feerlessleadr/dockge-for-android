package com.feerlessleadr.dockge.notify

import kotlinx.coroutines.flow.Flow
import java.io.IOException

/** One published message from any alert source (events of other kinds, such as keepalives, never become this). */
data class AlertMessage(
    /** Unique within its source. */
    val id: String,
    /** Unix seconds. */
    val time: Long,
    val topic: String,
    val title: String,
    val message: String,
    val priority: Int,
    val tags: List<String>,
)

/** What a subscription stream tells its reader. */
sealed interface AlertEvent {
    /** The server accepted the subscription. */
    data object Open : AlertEvent
    data class Message(val message: AlertMessage) : AlertEvent
}

class SourceHttpException(val code: Int) : IOException(
    when (code) {
        401, 403 -> "The server refused access (HTTP $code). Check the access token."
        404 -> "The server has no such address or topic (HTTP 404)."
        else -> "The server answered HTTP $code."
    },
)

/**
 * The saved connection of one alert source (ntfy or Gotify). The access token is not part of it: it lives in the
 * token vault. [topic] is only used by ntfy.
 */
data class SourceSettings(val enabled: Boolean = false, val serverUrl: String = "", val topic: String = "")

/** Where an alert stream comes from: ntfy, Gotify or a fake in tests. */
interface AlertSource {
    /**
     * Emits [AlertEvent.Open] once the server accepts, then one event per message. Ends when the server closes the
     * stream and throws on I/O and HTTP errors; the caller decides when to retry. [since] is what the source last
     * handed over (its message id) or, before the first message, the current Unix time in seconds.
     */
    fun stream(serverUrl: String, topic: String, token: String?, since: String): Flow<AlertEvent>
}
