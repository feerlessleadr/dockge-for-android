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

object NtfyParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** One line of the `/json` stream. Returns null for keepalives, open events, blank lines and anything unreadable. */
    fun parseLine(line: String): AlertMessage? {
        if (line.isBlank()) return null
        val obj = try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (obj.str("event") != "message") return null
        val id = obj.str("id") ?: return null
        return AlertMessage(
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

object NtfyValidation {
    private val topicPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Returns the cleaned settings or a failure whose message can be shown to the user. */
    fun check(
        serverUrl: String,
        topic: String,
        enabled: Boolean,
        allowCleartext: Boolean,
        requireTopic: Boolean = true,
    ): Result<SourceSettings> {
        val url = BaseUrl.parse(serverUrl, allowCleartext).getOrElse { return Result.failure(it) }
        val t = topic.trim()
        if (!requireTopic) return Result.success(SourceSettings(enabled, url.value, ""))
        if (!topicPattern.matches(t)) {
            return Result.failure(IllegalArgumentException("The topic can only have letters, digits, - and _ (at most 64)."))
        }
        return Result.success(SourceSettings(enabled, url.value, t))
    }
}

/** Reads the subscription stream of one topic and publishes (for the "send test" button). Plain OkHttp, no service. */
class NtfyClient(base: OkHttpClient = OkHttpClient()) : AlertSource {
    // ntfy sends a keepalive about every 45 s, so 90 s without a byte means a dead connection.
    private val http = base.newBuilder()
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Emits [AlertEvent.Open] once the server accepts, then one event per message. Ends when the server closes the
     * stream and throws on I/O errors and on HTTP errors ([SourceHttpException]); the caller decides when to retry.
     * [since] is a message id or a Unix time in seconds.
     */
    override fun stream(serverUrl: String, topic: String, token: String?, since: String): Flow<AlertEvent> = flow {
        val url = serverUrl.toHttpUrl().newBuilder()
            .addPathSegment(topic).addPathSegment("json")
            .addQueryParameter("since", since)
            .build()
        val call = http.newCall(authorized(Request.Builder().url(url), token).get().build())
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) throw SourceHttpException(response.code)
            emit(AlertEvent.Open)
            val source = response.body.source()
            while (currentCoroutineContext().isActive) {
                val line = source.readUtf8Line() ?: break
                NtfyParser.parseLine(line)?.let { emit(AlertEvent.Message(it)) }
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
                http.newCall(request).execute().use { if (!it.isSuccessful) throw SourceHttpException(it.code) }
            }
        }

    private fun authorized(builder: Request.Builder, token: String?): Request.Builder =
        if (token.isNullOrBlank()) builder else builder.header("Authorization", "Bearer $token")

    private companion object {
        const val READ_TIMEOUT_S = 90L
    }
}
