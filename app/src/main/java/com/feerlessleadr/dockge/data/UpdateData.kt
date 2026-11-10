package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.ApiException
import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.api.ServerUpdates
import com.feerlessleadr.dockge.api.SocketCallException
import com.feerlessleadr.dockge.api.StackParser
import com.feerlessleadr.dockge.api.agentCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The image watcher's last result per server of one instance. The server never pushes this: it is asked
 * (read-only calls `watcherImageStatusGet` and `watcherImageAutoUpdateGet`). A server that does not answer keeps its
 * previous result, so a short outage does not make update badges disappear.
 */
class UpdateStatusStore(private val socket: DockgeSocket, private val log: EventLog? = null) {
    private val _byServer = MutableStateFlow<Map<String, ServerUpdates>>(emptyMap())

    /** Keyed by endpoint ("" = the instance itself). Absent until a server was asked successfully. */
    val byServer: StateFlow<Map<String, ServerUpdates>> = _byServer.asStateFlow()

    suspend fun refresh(endpoints: Collection<String>) {
        for (endpoint in endpoints) {
            try {
                val images = StackParser.parseImageStatus(socket.agentCall("watcherImageStatusGet", endpoint = endpoint))
                // Whether the watcher is on decides what "no updates" means. If this call fails, assume it is on.
                val enabled = try {
                    StackParser.parseWatcherEnabled(socket.agentCall("watcherImageAutoUpdateGet", endpoint = endpoint))
                } catch (e: SocketCallException) {
                    true
                } catch (e: ApiException) {
                    true
                }
                val result = ServerUpdates(enabled, images)
                log?.add("updates endpoint=\"$endpoint\" images=${images.size} withUpdate=${images.count { it.updateAvailable }} watcher=${if (enabled) "on" else "off"}")
                _byServer.update { it + (endpoint to result) }
            } catch (e: SocketCallException) {
                log?.add("updates endpoint=\"$endpoint\" failed: ${e.message}")
            } catch (e: ApiException) {
                log?.add("updates endpoint=\"$endpoint\" failed: ${e.message}")
            }
        }
    }
}
