package com.feerlessleadr.dockge.notify

import com.feerlessleadr.dockge.core.BaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One published ntfy message (events of other kinds, such as keepalives, are never turned into this). */
data class NtfyMessage(
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
sealed interface NtfyEvent {
    /** The server accepted the subscription. */
    data object Open : NtfyEvent
    data class Message(val message: NtfyMessage) : NtfyEvent
}

class NtfyHttpException(val code: Int) : IOException(
    when (code) {
        401, 403 -> "The ntfy server refused access (HTTP $code). Check the access token."
        404 -> "The ntfy server has no such topic or address (HTTP 404)."
        else -> "The ntfy server answered HTTP $code."
    },
)

object NtfyParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** One line of the `/json` stream. Returns null for keepalives, open events, blank lines and anything unreadable. */
    fun parseLine(line: String): NtfyMessage? {
        if (line.isBlank()) return null
        val obj = try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (obj.str("event") != "message") return null
        val id = obj.str("id") ?: return null
        return NtfyMessage(
            id = id,
            time = (obj["time"] as? JsonPrimitive)?.longOrNull ?: 0L,
            topic = obj.str("topic") ?: "",
            title = obj.str("title") ?: "",
            message = obj.str("message") ?: "",
            priority = (obj["priority"] as? JsonPrimitive)?.intOrNull ?: 3,
            tags = (obj["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** The saved ntfy connection. The access token is not part of it: it lives in the token vault. */
data class NtfySettings(val enabled: Boolean = false, val serverUrl: String = "", val topic: String = "") {
    val usable: Boolean get() = serverUrl.isNotBlank() && topic.isNotBlank()
}

object NtfyValidation {
    private val topicPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Returns the cleaned settings or a failure whose message can be shown to the user. */
    fun check(serverUrl: String, topic: String, enabled: Boolean, allowCleartext: Boolean): Result<NtfySettings> {
        val url = BaseUrl.parse(serverUrl, allowCleartext).getOrElse { return Result.failure(it) }
        val t = topic.trim()
        if (!topicPattern.matches(t)) {
            return Result.failure(IllegalArgumentException("The topic can only have letters, digits, - and _ (at most 64)."))
        }
        return Result.success(NtfySettings(enabled, url.value, t))
    }
}

/** Where the subscription stream comes from (a fake in tests). */
interface NtfySource {
    fun stream(serverUrl: String, topic: String, token: String?, since: String): Flow<NtfyEvent>
}

/** Reads the subscription stream of one topic and publishes (for the "send test" button). Plain OkHttp, no service. */
class NtfyClient(base: OkHttpClient = OkHttpClient()) : NtfySource {
    // ntfy sends a keepalive about every 45 s, so 90 s without a byte means a dead connection.
    private val http = base.newBuilder()
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Emits [NtfyEvent.Open] once the server accepts, then one event per message. Ends when the server closes the
     * stream and throws on I/O errors and on HTTP errors ([NtfyHttpException]); the caller decides when to retry.
     * [since] is a message id or a Unix time in seconds.
     */
    override fun stream(serverUrl: String, topic: String, token: String?, since: String): Flow<NtfyEvent> = flow {
        val url = serverUrl.toHttpUrl().newBuilder()
            .addPathSegment(topic).addPathSegment("json")
            .addQueryParameter("since", since)
            .build()
        val call = http.newCall(authorized(Request.Builder().url(url), token).get().build())
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) throw NtfyHttpException(response.code)
            emit(NtfyEvent.Open)
            val source = response.body.source()
            while (currentCoroutineContext().isActive) {
                val line = source.readUtf8Line() ?: break
                NtfyParser.parseLine(line)?.let { emit(NtfyEvent.Message(it)) }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Publishes a plain message to the topic. */
    suspend fun publish(serverUrl: String, topic: String, token: String?, title: String, body: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = serverUrl.toHttpUrl().newBuilder().addPathSegment(topic).build()
                val request = authorized(Request.Builder().url(url), token)
                    .header("Title", title)
                    .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(request).execute().use { if (!it.isSuccessful) throw NtfyHttpException(it.code) }
            }
        }

    private fun authorized(builder: Request.Builder, token: String?): Request.Builder =
        if (token.isNullOrBlank()) builder else builder.header("Authorization", "Bearer $token")

    private companion object {
        const val READ_TIMEOUT_S = 90L
    }
}
