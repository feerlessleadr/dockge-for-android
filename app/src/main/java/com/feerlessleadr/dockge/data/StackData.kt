package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.api.Stack
import com.feerlessleadr.dockge.api.StackDetail
import com.feerlessleadr.dockge.api.StackParser
import com.feerlessleadr.dockge.api.SocketCallException
import com.feerlessleadr.dockge.api.agentCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps the last pushed stack list of one instance. The server pushes it right after login, after every action and
 * every 10 seconds, so this must be listening before the login happens. Null means "nothing received yet".
 */
class StackListStore(socket: DockgeSocket, scope: CoroutineScope) {
    private val _stacks = MutableStateFlow<List<Stack>?>(null)
    val stacks: StateFlow<List<Stack>?> = _stacks.asStateFlow()

    private val job: Job = scope.launch {
        socket.events("agent").collect { args -> StackParser.parseStackList(args)?.let { _stacks.value = it } }
    }

    fun stop() = job.cancel()
}

/** Read-only calls for one stack. Never sends start, stop, restart or update. */
object StackDetailLoader {
    /** Asks for service status and container list together. Fails as a whole if either call fails. */
    suspend fun load(socket: DockgeSocket, stackName: String): Result<StackDetail> = try {
        coroutineScope {
            val name = listOf(JsonPrimitive(stackName))
            val services = async { socket.agentCall("serviceStatusList", name) }
            val containers = async { socket.agentCall("containerInstances", name) }
            val (list, lastUpdated, lastStarted) = StackParser.parseServiceStatus(services.await())
            Result.success(StackDetail(list, StackParser.parseContainers(containers.await()), lastUpdated, lastStarted))
        }
    } catch (e: SocketCallException) {
        Result.failure(e)
    } catch (e: com.feerlessleadr.dockge.api.ApiException) {
        Result.failure(e)
    }
}
