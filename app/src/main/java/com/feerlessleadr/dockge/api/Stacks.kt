package com.feerlessleadr.dockge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant

/** Status codes from the server (common/util-common.ts, see docs/dockge-api.md 2.2). */
enum class StackStatus(val code: Int) {
    Unknown(0), Draft(1), Created(2), Running(3), Exited(4), Paused(5);

    companion object {
        fun fromCode(code: Int?): StackStatus = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** One entry of the pushed `stackList`. It has no per-container data. */
data class Stack(
    /** "" for the instance itself; otherwise the endpoint of a linked (federated) server. */
    val endpoint: String,
    val name: String,
    val displayName: String,
    val status: StackStatus,
    /** False for compose projects Dockge found but does not manage. */
    val isManaged: Boolean,
    val isExternal: Boolean,
    val composeFileName: String,
    val lastUpdated: Instant?,
) {
    val title: String get() = displayName.ifBlank { name }
}

/** One service from `serviceStatusList`. `state` already has the health value merged in by the server. */
data class ServiceStatus(
    val service: String,
    val state: String,
    val ports: List<String>,
    val startedAt: Instant?,
)

/** One container from `containerInstances`. */
data class ContainerInstance(
    val id: String,
    val name: String,
    val service: String,
    val state: String,
    /** "healthy", "unhealthy", "starting" or "" when there is no health check. */
    val health: String,
    val image: String,
    val ports: String,
    val runningFor: String,
)

data class StackDetail(
    val services: List<ServiceStatus>,
    val containers: List<ContainerInstance>,
    val lastUpdated: Instant?,
    val lastStartedAt: Instant?,
)

class ApiException(message: String) : Exception(message)

/** Turns server payloads into typed values. Shapes: docs/dockge-api.md part B (from source, not live captures). */
object StackParser {
    /** The stacks one server pushed. [endpoint] is "" for the instance itself, or a linked server (federation). */
    data class StackListPush(val endpoint: String, val stacks: List<Stack>)

    /** Parses a pushed `agent` / `stackList` event (stacks sorted by name), or returns null for any other event. */
    fun parseStackList(args: List<JsonElement>): StackListPush? {
        if (args.size < 2) return null
        if ((args[0] as? JsonPrimitive)?.contentOrNull != "stackList") return null
        val payload = args[1] as? JsonObject ?: return null
        val list = payload["stackList"] as? JsonObject ?: return null
        val endpoint = payload.string("endpoint") ?: ""
        val stacks = list.mapNotNull { (key, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            Stack(
                endpoint = endpoint,
                name = obj.string("name") ?: key,
                displayName = obj.string("displayName") ?: "",
                status = StackStatus.fromCode(obj.int("status")),
                isManaged = obj.bool("isManagedByDockge") ?: true,
                isExternal = obj.bool("isExternal") ?: false,
                composeFileName = obj.string("composeFileName") ?: "",
                lastUpdated = obj.instant("lastUpdated"),
            )
        }.sortedBy { it.name.lowercase() }
        return StackListPush(endpoint, stacks)
    }

    /** A linked server from the pushed `agentList` (the instance itself is the entry with the empty endpoint). */
    data class AgentInfo(val endpoint: String, val displayName: String, val url: String)

    fun parseAgentList(args: List<JsonElement>): Map<String, AgentInfo>? {
        val payload = args.firstOrNull() as? JsonObject ?: return null
        val list = payload["agentList"] as? JsonObject ?: return null
        return list.mapNotNull { (key, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            key to AgentInfo(key, obj.string("displayName") ?: "", obj.string("url") ?: "")
        }.toMap()
    }

    /** The pushed `agentStatus`: ("endpoint", "online" | "offline" | "connecting"). */
    fun parseAgentStatus(args: List<JsonElement>): Pair<String, String>? {
        val payload = args.firstOrNull() as? JsonObject ?: return null
        val endpoint = payload.string("endpoint") ?: return null
        val status = payload.string("status") ?: return null
        return endpoint to status
    }

    /** Parses the acknowledgement of `serviceStatusList`. */
    fun parseServiceStatus(reply: JsonElement): Triple<List<ServiceStatus>, Instant?, Instant?> {
        val obj = okOrThrow(reply)
        val services = (obj["serviceStatusList"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
            val s = value as? JsonObject ?: return@mapNotNull null
            ServiceStatus(
                service = name,
                state = s.string("state") ?: "",
                ports = (s["ports"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                startedAt = s.instant("startedAt"),
            )
        }.sortedBy { it.service.lowercase() }
        return Triple(services, obj.instant("lastUpdated"), obj.instant("lastStartedAt"))
    }

    /** Parses the acknowledgement of `containerInstances`. */
    fun parseContainers(reply: JsonElement): List<ContainerInstance> {
        val obj = okOrThrow(reply)
        return (obj["instances"] as? JsonArray).orEmpty().mapNotNull { value ->
            val c = value as? JsonObject ?: return@mapNotNull null
            val id = c.string("id") ?: return@mapNotNull null
            ContainerInstance(
                id = id,
                name = c.string("name") ?: id,
                service = c.string("service") ?: "",
                state = c.string("state") ?: "",
                health = c.string("health") ?: "",
                image = c.string("image") ?: "",
                ports = c.string("ports") ?: "",
                runningFor = c.string("runningFor") ?: "",
            )
        }.sortedWith(compareBy({ it.service.lowercase() }, { it.name.lowercase() }))
    }

    /** Parses the acknowledgement of `watcherImageStatusGet`: one entry per image of every stack of that server. */
    fun parseImageStatus(reply: JsonElement): List<ImageUpdate> {
        val obj = okOrThrow(reply)
        return (obj["data"] as? JsonArray).orEmpty().mapNotNull { value ->
            val s = value as? JsonObject ?: return@mapNotNull null
            val stack = s.string("stack") ?: return@mapNotNull null
            val image = s.string("image") ?: return@mapNotNull null
            ImageUpdate(
                stack = stack,
                image = image,
                localDigest = s.string("localDigest") ?: "",
                remoteDigest = s.string("remoteDigest") ?: "",
                hasUpdate = s.bool("hasUpdate") ?: false,
                lastChecked = s.instant("lastChecked"),
                ignored = s.bool("ignored") ?: false,
                error = s.string("error")?.ifBlank { null },
            )
        }
    }

    /** From the acknowledgement of `watcherImageAutoUpdateGet`: whether the image watcher is switched on at all. */
    fun parseWatcherEnabled(reply: JsonElement): Boolean {
        val obj = okOrThrow(reply)
        return ((obj["data"] as? JsonObject)?.bool("enabled")) ?: false
    }

    private fun okOrThrow(reply: JsonElement): JsonObject {
        val obj = reply as? JsonObject ?: throw ApiException("Unexpected reply from the server.")
        if (obj.bool("ok") != true) throw ApiException(obj.string("msg") ?: "The server reported an error.")
        return obj
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.instant(key: String): Instant? = string(key)?.let {
        try {
            Instant.parse(it)
        } catch (e: Exception) {
            null
        }
    }
}
