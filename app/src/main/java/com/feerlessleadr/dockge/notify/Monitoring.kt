package com.feerlessleadr.dockge.notify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A crash loop the server saw (`CrashEvent` in the fork's monitoring-watcher). */
data class CrashEvent(val containerName: String, val containerId: String, val restartCount: Int, val windowMinutes: Int, val timestamp: String)

/** A container that turned unhealthy (`HealthEvent`). */
data class HealthEvent(
    val containerName: String,
    val containerId: String,
    val stackName: String?,
    val serviceName: String?,
    val healthStatus: String,
    val actionStatus: String,
    val message: String,
    val timestamp: String,
)

data class SecurityFinding(val image: String, val stack: String, val severity: String)

/** The part of `GET /api/monitoring/overview` the app uses. Null fields mean the server did not say. */
data class MonitoringOverview(
    /** Timestamp of the newest backup attempt, or null when there was none. */
    val backupTimestamp: String?,
    /** True or false for that attempt; null when unknown. */
    val backupSuccess: Boolean?,
    val security: List<SecurityFinding>,
    val crashes: List<CrashEvent>,
    val health: List<HealthEvent>,
)

class MonitoringHttpException(val code: Int) : IOException(
    when (code) {
        401, 403 -> "The server refused the saved login (HTTP $code). Sign in again."
        404 -> "This server has no monitoring feed (HTTP 404). Is it dockge-enhanced?"
        else -> "The server answered HTTP $code."
    },
)

object MonitoringParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses the overview reply; throws [IOException] when it is not the expected `{ok:true,data:{...}}` shape. */
    fun parseOverview(text: String): MonitoringOverview {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IOException("The monitoring reply was not readable.")
        if ((root["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw IOException((root["message"] as? JsonPrimitive)?.contentOrNull ?: "The server reported an error.")
        }
        val data = root["data"] as? JsonObject ?: throw IOException("The monitoring reply had no data.")
        val backup = data["backup"] as? JsonObject
        val trivy = data["trivy"] as? JsonObject
        return MonitoringOverview(
            backupTimestamp = backup?.str("lastTimestamp"),
            backupSuccess = (backup?.get("success") as? JsonPrimitive)?.booleanOrNull,
            security = (trivy?.get("criticalImages") as? JsonArray).objects().mapNotNull {
                val image = it.str("image") ?: return@mapNotNull null
                SecurityFinding(image, it.str("stack") ?: "", it.str("maxSeverity") ?: "UNKNOWN")
            },
            crashes = (data["crashes"] as? JsonArray).objects().mapNotNull {
                CrashEvent(
                    containerName = it.str("containerName") ?: return@mapNotNull null,
                    containerId = it.str("containerId") ?: "",
                    restartCount = (it["restartCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                    windowMinutes = (it["windowMinutes"] as? JsonPrimitive)?.intOrNull ?: 0,
                    timestamp = it.str("timestamp") ?: return@mapNotNull null,
                )
            },
            health = (data["health"] as? JsonArray).objects().mapNotNull {
                HealthEvent(
                    containerName = it.str("containerName") ?: return@mapNotNull null,
                    containerId = it.str("containerId") ?: "",
                    stackName = it.str("stackName"),
                    serviceName = it.str("serviceName"),
                    healthStatus = it.str("healthStatus") ?: "unhealthy",
                    actionStatus = it.str("actionStatus") ?: "",
                    message = it.str("message") ?: "",
                    timestamp = it.str("timestamp") ?: return@mapNotNull null,
                )
            },
        )
    }

    private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
}

/** Reads `GET <instance>/api/monitoring/overview` with the instance's saved session token (admin users only). */
fun interface MonitoringSource {
    suspend fun overview(baseUrl: String, token: String?): MonitoringOverview
}

class MonitoringClient(base: OkHttpClient = OkHttpClient()) : MonitoringSource {
    private val http = base.newBuilder()
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun overview(baseUrl: String, token: String?): MonitoringOverview = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/monitoring/overview").build()
        val request = Request.Builder().url(url).get()
        // The token goes in a header, never in the URL. Servers with login switched off need none.
        if (!token.isNullOrBlank()) request.header("Authorization", "Bearer $token")
        http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw MonitoringHttpException(response.code)
            MonitoringParser.parseOverview(response.body.string())
        }
    }

    private companion object {
        const val CALL_TIMEOUT_S = 20L
    }
}
