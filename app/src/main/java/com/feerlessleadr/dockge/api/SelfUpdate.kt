package com.feerlessleadr.dockge.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/** One stage of a self-update, as the server records it (`SelfUpdateStageTiming`). */
data class SelfUpdateStage(
    val stage: String,
    val skipped: Boolean,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

/** The current or last self-update of the Dockge instance (`SelfUpdateOperation`, read from the server's status file). */
data class SelfUpdateOperation(
    val id: String,
    /** idle, scheduled, backing-up, verifying-backup, updating, waiting-health, rolling-back, succeeded, failed, rolled-back, rollback-failed. */
    val state: String,
    val message: String,
    val targetImage: String,
    val stage: String?,
    val stageHistory: List<SelfUpdateStage>,
    val rollbackAttempted: Boolean,
)

/**
 * `GET /api/watcher/self/status`: whether a newer Dockge-Enhanced image exists (registry digest against the running
 * image), and the last self-update. [repo] is the image repository the server checks, for example "aerya/dockge-enhanced".
 */
data class SelfUpdateStatus(
    val updateAvailable: Boolean,
    val localDigest: String,
    val remoteDigest: String,
    val repo: String,
    val containerName: String,
    val checkedAt: Instant?,
    val error: String?,
    val operation: SelfUpdateOperation?,
)

object SelfUpdatePolicy {
    /** Operations the server is still working on. The server refuses a second one meanwhile. */
    val ACTIVE_STATES = setOf("backing-up", "verifying-backup", "updating", "waiting-health", "rolling-back")
    val FINISHED_STATES = setOf("succeeded", "failed", "rolled-back", "rollback-failed")

    private val DIGEST = Regex("sha256:[a-f0-9]{64}")
    private val REPO = Regex("[a-z0-9._-]+/[a-z0-9._-]+")

    fun isActive(operation: SelfUpdateOperation?): Boolean = operation?.state in ACTIVE_STATES

    fun isFinished(operation: SelfUpdateOperation?): Boolean = operation?.state in FINISHED_STATES

    /**
     * The image the server must update to: the digest it found on the registry, under the repository it checks. Null
     * when there is no update or the status is incomplete. The app never types an image: it only sends this value,
     * and the server still refuses any image outside the Dockge repository.
     */
    fun targetImage(status: SelfUpdateStatus): String? {
        if (!status.updateAvailable) return null
        if (!REPO.matches(status.repo) || !DIGEST.matches(status.remoteDigest)) return null
        return "ghcr.io/${status.repo}@${status.remoteDigest}"
    }

    /**
     * Whether an image reference (a compose `image:` or a container image) is the Dockge-Enhanced image itself, so
     * its stack must use the safe self-update and never the plain stack update, which would stop Dockge mid-update.
     */
    fun isDockgeImage(image: String, repo: String): Boolean {
        if (repo.isBlank()) return false
        val withoutDigest = image.trim().substringBefore("@")
        val lastSlash = withoutDigest.lastIndexOf('/')
        val lastColon = withoutDigest.lastIndexOf(':')
        val name = if (lastColon > lastSlash) withoutDigest.substring(0, lastColon) else withoutDigest
        val path = name.removePrefix("ghcr.io/").lowercase()
        return path == repo.lowercase()
    }

    /** Short label for a stage name from the server. */
    fun stageLabel(stage: String?): String = when (stage) {
        null, "", "preparing" -> "Preparing"
        "backup" -> "Backing up"
        "verify-backup" -> "Verifying the backup"
        "prune-backup" -> "Removing old backups"
        "prepare-updater" -> "Starting the updater"
        "pull-target" -> "Downloading the new image"
        "replace-container" -> "Replacing Dockge"
        "health-check" -> "Waiting for Dockge to be healthy"
        "rollback" -> "Restoring the previous version"
        else -> stage
    }
}

/** Typed reading of the server's self-update JSON. Shapes: docs/dockge-api.md part C (from the fork's source). */
object SelfUpdateParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseStatus(body: String): SelfUpdateStatus {
        val obj = parseObject(body)
        return SelfUpdateStatus(
            updateAvailable = obj.bool("updateAvailable") == true,
            localDigest = obj.str("localDigest").orEmpty(),
            remoteDigest = obj.str("remoteDigest").orEmpty(),
            repo = obj.str("repo").orEmpty(),
            containerName = obj.str("containerName").orEmpty(),
            checkedAt = obj.str("checkedAt")?.let(::instant),
            error = obj.str("error"),
            operation = (obj["operation"] as? JsonObject)?.let(::operation),
        )
    }

    /** Reads the operation from a status reply or from the start reply (`data` of `POST /self/update`). */
    fun parseOperation(body: String): SelfUpdateOperation {
        val obj = parseObject(body)
        val data = obj["data"] as? JsonObject
        return operation(data ?: obj)
    }

    private fun operation(obj: JsonObject): SelfUpdateOperation = SelfUpdateOperation(
        id = obj.str("id").orEmpty(),
        state = obj.str("state").orEmpty(),
        message = obj.str("message").orEmpty(),
        targetImage = obj.str("targetImage").orEmpty(),
        stage = obj.str("stage"),
        stageHistory = (obj["stageHistory"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.let {
                SelfUpdateStage(
                    stage = it.str("stage") ?: return@mapNotNull null,
                    skipped = it.bool("skipped") == true,
                    startedAt = it.str("startedAt")?.let(::instant),
                    finishedAt = it.str("finishedAt")?.let(::instant),
                )
            }
        },
        rollbackAttempted = obj.bool("rollbackAttempted") == true,
    )

    /** Throws [ApiException] with the server's message when `ok` is false. */
    private fun parseObject(body: String): JsonObject {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw ApiException("Unexpected reply from the server.")
        if (obj.bool("ok") == false) throw ApiException(obj.str("message") ?: "The server reported an error.")
        return obj
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun instant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
}

/** The server answered with an HTTP error (not a refusal inside an OK reply). */
class SelfUpdateHttpException(val code: Int, message: String) : java.io.IOException(message)

/**
 * The self-update REST calls. Authentication is the instance's admin login token, sent as a Bearer header (the same
 * token the monitoring feed uses). Automation tokens cannot reach these routes.
 */
interface SelfUpdateApi {
    suspend fun status(baseUrl: String, token: String): SelfUpdateStatus
    suspend fun start(baseUrl: String, token: String, targetImage: String): SelfUpdateOperation
}

class SelfUpdateClient(base: OkHttpClient = OkHttpClient()) : SelfUpdateApi {
    private val http = base.newBuilder()
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** The start call runs the whole backup on the server before it answers, so it gets a long timeout. */
    private val startHttp = http.newBuilder().callTimeout(START_TIMEOUT_MIN, TimeUnit.MINUTES).build()

    override suspend fun status(baseUrl: String, token: String): SelfUpdateStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(baseUrl, "api/watcher/self/status")).get().header("Authorization", "Bearer $token")
        http.newCall(request.build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw SelfUpdateHttpException(response.code, httpMessage(response.code, body))
            SelfUpdateParser.parseStatus(body)
        }
    }

    /** Asks the server to start the update to [targetImage]. The reply can arrive late or never (Dockge restarts), so the caller watches [status] too. */
    override suspend fun start(baseUrl: String, token: String, targetImage: String): SelfUpdateOperation = withContext(Dispatchers.IO) {
        // The target is validated by SelfUpdatePolicy.targetImage before it gets here; JsonObject escapes the value.
        val payload = JsonObject(mapOf("targetImage" to JsonPrimitive(targetImage))).toString()
        val request = Request.Builder()
            .url(url(baseUrl, "api/watcher/self/update"))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
        startHttp.newCall(request.build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw SelfUpdateHttpException(response.code, httpMessage(response.code, body))
            SelfUpdateParser.parseOperation(body)
        }
    }

    private fun url(baseUrl: String, path: String) = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()

    private fun httpMessage(code: Int, body: String): String {
        val fromServer = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("message") as? JsonPrimitive }
            .getOrNull()?.contentOrNull
        return when (code) {
            401, 403 -> "The server refused the saved login (HTTP $code). Sign in again."
            404 -> "This server has no Dockge self-update (HTTP 404). Is it dockge-enhanced?"
            else -> fromServer ?: "The server answered HTTP $code."
        }
    }

    private companion object {
        const val CALL_TIMEOUT_S = 20L
        const val START_TIMEOUT_MIN = 60L
    }
}
