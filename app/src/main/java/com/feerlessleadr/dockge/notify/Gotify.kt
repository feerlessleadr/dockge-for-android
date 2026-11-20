package com.feerlessleadr.dockge.notify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

object GotifyParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Gotify message ids are numbers; ours carry a "g" so they cannot be mistaken for another source's ids. */
    const val ID_PREFIX = "g"

    /** One message as Gotify sends it (a WebSocket frame, or one entry of `GET /message`). Null if unreadable. */
    fun parseMessage(element: kotlinx.serialization.json.JsonElement?): AlertMessage? {
        val obj = element as? JsonObject ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val date = (obj["date"] as? JsonPrimitive)?.contentOrNull
        return AlertMessage(
            id = "$ID_PREFIX$id",
            time = runCatching { OffsetDateTime.parse(date).toEpochSecond() }.getOrDefault(0L),
            topic = (obj["appid"] as? JsonPrimitive)?.contentOrNull ?: "",
            title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: "",
            message = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: "",
            priority = (obj["priority"] as? JsonPrimitive)?.intOrNull ?: 0,
            tags = emptyList(),
        )
    }

    fun parseFrame(text: String): AlertMessage? =
        try { parseMessage(json.parseToJsonElement(text)) } catch (e: Exception) { null }

    /** The `GET /message` reply: newest first. */
    fun parseMessageList(text: String): List<AlertMessage> = try {
        val list = (json.parseToJsonElement(text) as? JsonObject)?.get("messages") as? JsonArray
        list?.mapNotNull { parseMessage(it) }.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /** The numeric id out of a cursor like "g42", or null for anything else (for example the first-run timestamp). */
    fun cursorId(since: String): Long? = since.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)?.toLongOrNull()
}

/**
 * Listens to a Gotify server with the user's client token: one WebSocket on `/stream`. Right after it opens the
 * messages newer than the cursor are fetched with `GET /message`, so alerts that arrived while the app was not
 * connected are not lost (the listener drops any message that both paths deliver). The first run starts "now".
 */
class GotifyClient(base: OkHttpClient = OkHttpClient()) : AlertSource {
    // Gotify pings the client; the client also pings so a half dead connection is noticed within a minute.
    private val http = base.newBuilder()
        .pingInterval(PING_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun stream(serverUrl: String, topic: String, token: String?, since: String): Flow<AlertEvent> = callbackFlow {
        val base = serverUrl.toHttpUrl()
        val cursor = GotifyParser.cursorId(since)
        val streamRequest = authorized(Request.Builder().url(base.newBuilder().addPathSegment("stream").build()), token).build()
        val producer = this
        val socket = http.newWebSocket(
            streamRequest,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    trySend(AlertEvent.Open)
                    if (cursor != null) {
                        producer.launch(Dispatchers.IO) {
                            runCatching { catchUp(base.toString(), token, cursor) }.getOrDefault(emptyList())
                                .forEach { trySend(AlertEvent.Message(it)) }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    GotifyParser.parseFrame(text)?.let { trySend(AlertEvent.Message(it)) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    close(if (code != null && code >= 400) SourceHttpException(code) else t)
                }
            },
        )
        awaitClose { socket.cancel() }
    }.buffer(Channel.UNLIMITED)

    /** Messages with an id above [cursor], oldest first (Gotify lists newest first, at most [CATCH_UP_LIMIT]). */
    private fun catchUp(serverUrl: String, token: String?, cursor: Long): List<AlertMessage> {
        val url = serverUrl.toHttpUrl().newBuilder().addPathSegment("message").addQueryParameter("limit", CATCH_UP_LIMIT.toString()).build()
        http.newCall(authorized(Request.Builder().url(url), token).get().build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            return GotifyParser.parseMessageList(response.body.string())
                .filter { (it.id.removePrefix(GotifyParser.ID_PREFIX).toLongOrNull() ?: 0L) > cursor }
                .sortedBy { it.id.removePrefix(GotifyParser.ID_PREFIX).toLongOrNull() ?: 0L }
        }
    }

    private fun authorized(builder: Request.Builder, token: String?): Request.Builder =
        if (token.isNullOrBlank()) builder else builder.header("X-Gotify-Key", token)

    private companion object {
        const val PING_S = 30L
        const val CATCH_UP_LIMIT = 100
    }
}
