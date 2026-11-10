package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.ApiException
import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.api.SocketCallException
import com.feerlessleadr.dockge.api.Stack
import com.feerlessleadr.dockge.api.StackDetail
import com.feerlessleadr.dockge.api.StackParser
import com.feerlessleadr.dockge.api.agentCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** The stacks of one server. [endpoint] is "" for the instance itself; a federated instance also has linked servers. */
data class ServerStacks(
    val endpoint: String,
    val name: String,
    /** "online", "offline", "connecting", or "" when the server did not say (always "" for the instance itself). */
    val status: String,
    val stacks: List<Stack>,
) {
    val isLocal: Boolean get() = endpoint.isEmpty()
}

/**
 * Keeps the last pushed stack list of one instance, per server. An instance that other Dockge servers are linked to
 * pushes one list per server, each stamped with its endpoint, so the lists must not overwrite each other.
 * The server pushes the first lists right after login, so this must be listening before the login happens.
 * Null means "nothing received yet".
 */
class StackListStore(socket: DockgeSocket, scope: CoroutineScope, private val log: EventLog? = null) {
    private val otherAgentEvents = mutableSetOf<String>()

    private val lists = MutableStateFlow<Map<String, List<Stack>>>(emptyMap())
    private val names = MutableStateFlow<Map<String, String>>(emptyMap())
    private val statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _servers = MutableStateFlow<List<ServerStacks>?>(null)
    val servers: StateFlow<List<ServerStacks>?> = _servers.asStateFlow()

    private val jobs: List<Job> = listOf(
        scope.launch {
            socket.events("agent").collect { args ->
                val push = StackParser.parseStackList(args)
                if (push != null) {
                    log?.add("stackList endpoint=\"${push.endpoint}\" stacks=${push.stacks.size} ${push.stacks.take(3).joinToString(prefix = "[", postfix = "]") { it.name }}")
                    lists.update { it + (push.endpoint to push.stacks) }
                    publish()
                } else {
                    val name = (args.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (name != null && otherAgentEvents.add(name)) log?.add("agent event \"$name\" (first seen)")
                }
            }
        },
        scope.launch {
            socket.events("agentList").collect { args ->
                StackParser.parseAgentList(args)?.let { list ->
                    log?.add("agentList keys=${list.keys.map { "\"$it\"" }}")
                    names.value = list.mapValues { (endpoint, info) -> info.displayName.ifBlank { info.url.ifBlank { endpoint } } }
                    // Lists are kept even for keys missing here: a removed server may linger until reconnect, but a
                    // key written differently in the two events must never hide a server.
                    publish()
                }
            }
        },
        scope.launch {
            socket.events("agentStatus").collect { args ->
                StackParser.parseAgentStatus(args)?.let { (endpoint, status) ->
                    log?.add("agentStatus endpoint=\"$endpoint\" status=$status")
                    statuses.update { it + (endpoint to status) }
                    publish()
                }
            }
        },
    )

    private fun publish() {
        val current = lists.value
        if (current.isEmpty()) return
        val local = current[""]?.let { ServerStacks("", names.value[""].orEmpty().ifBlank { "This server" }, "", it) }
        val linked = current.filterKeys { it.isNotEmpty() }.map { (endpoint, stacks) ->
            ServerStacks(endpoint, names.value[endpoint] ?: endpoint, statuses.value[endpoint].orEmpty(), stacks)
        }.sortedBy { it.name.lowercase() }
        _servers.value = listOfNotNull(local) + linked
    }

    fun stop() = jobs.forEach(Job::cancel)
}

/** Read-only calls for one stack. Never sends start, stop, restart or update. */
object StackDetailLoader {
    /** Asks for service status and container list together. Fails as a whole if either call fails. */
    suspend fun load(socket: DockgeSocket, stackName: String, endpoint: String = ""): Result<StackDetail> = try {
        coroutineScope {
            val name = listOf(JsonPrimitive(stackName))
            val services = async { socket.agentCall("serviceStatusList", name, endpoint = endpoint) }
            val containers = async { socket.agentCall("containerInstances", name, endpoint = endpoint) }
            val (list, lastUpdated, lastStarted) = StackParser.parseServiceStatus(services.await())
            Result.success(StackDetail(list, StackParser.parseContainers(containers.await()), lastUpdated, lastStarted))
        }
    } catch (e: SocketCallException) {
        Result.failure(e)
    } catch (e: ApiException) {
        Result.failure(e)
    }
}
