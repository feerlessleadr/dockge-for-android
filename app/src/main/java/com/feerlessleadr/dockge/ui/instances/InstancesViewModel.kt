package com.feerlessleadr.dockge.ui.instances

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.feerlessleadr.dockge.DockgeApp
import com.feerlessleadr.dockge.data.ServerStacks
import com.feerlessleadr.dockge.api.StackDetail
import com.feerlessleadr.dockge.api.StackStatus
import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.data.ConnectionState
import com.feerlessleadr.dockge.data.StackDetailLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class InstanceRow(
    val instance: Instance,
    val state: ConnectionState,
    val stackCount: Int?,
    val runningCount: Int?,
    /** More than 1 when other Dockge servers are linked to this instance. */
    val serverCount: Int = 1,
)

class InstancesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as DockgeApp).container

    val rows: StateFlow<List<InstanceRow>> = combine(
        container.instances.instances, container.sessions.states, container.sessions.stackLists,
    ) { list, states, stacks ->
        list.map {
            val servers = stacks[it.id]
            val all = servers?.flatMap { sv -> sv.stacks }
            InstanceRow(
                instance = it,
                state = states[it.id] ?: ConnectionState.Connecting,
                stackCount = all?.size,
                runningCount = all?.count { st -> st.status == StackStatus.Running },
                serverCount = servers?.size ?: 1,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun get(id: String): Instance? = container.instances.get(id)

    fun add(name: String, address: String, color: Long): Result<Instance> = container.instances.add(name, address, color)

    fun update(id: String, name: String, address: String, color: Long): Result<Instance> =
        container.instances.update(id, name, address, color)

    fun delete(id: String) = container.instances.delete(id)

    fun reconnect(id: String) = container.sessions.reconnect(id)

    fun signOut(id: String) {
        container.sessions.session(id)?.signOut()
        // signOut() stops the session; a fresh one shows the sign-in form again.
        container.sessions.reconnect(id)
    }

    fun submitCredentials(id: String, username: String, password: String) =
        container.sessions.session(id)?.submitCredentials(username, password)

    fun submitCode(id: String, code: String) = container.sessions.session(id)?.submitCode(code)

    /** The diagnostics log of one instance (debug builds show it). */
    fun log(id: String) = container.sessions.log(id)

    /** The connection state of one instance (for banners on the stack screens). */
    fun connection(id: String): Flow<ConnectionState> = container.sessions.states.map { it[id] ?: ConnectionState.Disconnected }

    /** The last pushed stack list of one instance, or null before the first push. */
    fun stacks(id: String): Flow<List<ServerStacks>?> = container.sessions.stackLists.map { it[id] }

    /** Read-only: service status and container list of one stack on one server ("" = the instance itself). */
    suspend fun loadDetail(instanceId: String, endpoint: String, stackName: String): Result<StackDetail> {
        val session = container.sessions.session(instanceId)
            ?: return Result.failure(IllegalStateException("This instance is not connected."))
        return StackDetailLoader.load(session.socket, stackName, endpoint)
    }
}
