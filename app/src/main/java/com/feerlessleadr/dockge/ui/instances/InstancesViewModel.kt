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
import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.api.ServerUpdates
import com.feerlessleadr.dockge.data.Action
import com.feerlessleadr.dockge.data.ActionOutcome
import com.feerlessleadr.dockge.data.ContainerAction
import com.feerlessleadr.dockge.data.ActionState
import com.feerlessleadr.dockge.data.StackAction
import com.feerlessleadr.dockge.data.StackActionRunner
import com.feerlessleadr.dockge.data.StackDetailLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InstanceRow(
    val instance: Instance,
    val state: ConnectionState,
    val stackCount: Int?,
    val runningCount: Int?,
    /** More than 1 when other Dockge servers are linked to this instance. */
    val serverCount: Int = 1,
    /** Stacks with an image update on all servers, or null until the server was asked. */
    val updateCount: Int? = null,
)

data class ActionKey(val instanceId: String, val endpoint: String, val stackName: String)

/** [target] is what the action works on (the stack's or the container's name). */
data class ActionUi(val key: ActionKey, val action: Action, val target: String, val state: ActionState)

class InstancesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as DockgeApp).container

    val rows: StateFlow<List<InstanceRow>> = combine(
        container.instances.instances, container.sessions.states, container.sessions.stackLists, container.sessions.updates,
    ) { list, states, stacks, updates ->
        list.map {
            val servers = stacks[it.id]
            val all = servers?.flatMap { sv -> sv.stacks }
            InstanceRow(
                instance = it,
                state = states[it.id] ?: ConnectionState.Connecting,
                stackCount = all?.size,
                runningCount = all?.count { st -> st.status == StackStatus.Running },
                serverCount = servers?.size ?: 1,
                updateCount = updates[it.id]?.values?.sumOf { u -> u.availableCount },
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

    /** The image watcher's result per server endpoint of one instance, or null before the first answer. */
    fun updates(id: String): Flow<Map<String, ServerUpdates>?> = container.sessions.updates.map { it[id] }

    /** Asks the instance's servers for their update status now (read only). */
    suspend fun refreshUpdates(id: String) = container.sessions.refreshUpdates(id)

    /** Starts an update of the whole stack: pull, then recreate. The caller shows the confirm dialog first. */
    fun updateStack(instanceId: String, endpoint: String, stackName: String) = runAction(instanceId, endpoint, stackName, StackAction.Update)

    private val _action = MutableStateFlow<ActionUi?>(null)

    /** The write action that is running or just ended (one at a time, app wide). The detail screen of its stack shows it. */
    val action: StateFlow<ActionUi?> = _action.asStateFlow()

    /** Starts [action] on a stack. Ignored while another action is running. Lives in the ViewModel so leaving the screen or rotating does not lose the output. */
    fun runAction(instanceId: String, endpoint: String, stackName: String, action: StackAction) =
        launchAction(ActionKey(instanceId, endpoint, stackName), action, stackName) { socket ->
            StackActionRunner.run(socket, stackName, endpoint, action)
        }

    /** Start, stop or restart one container of a stack. [target] is its name, for the titles. */
    fun runContainerAction(instanceId: String, endpoint: String, stackName: String, containerId: String, target: String, action: ContainerAction) =
        launchAction(ActionKey(instanceId, endpoint, stackName), action, target) { socket ->
            StackActionRunner.runContainer(socket, stackName, containerId, endpoint, action)
        }

    private fun launchAction(key: ActionKey, action: Action, target: String, start: (DockgeSocket) -> Flow<ActionState>) {
        if (_action.value?.state?.running == true) return
        val socket = container.sessions.session(key.instanceId)?.socket
        if (socket == null) {
            _action.value = ActionUi(key, action, target, ActionState(outcome = ActionOutcome.Failed("This instance is not connected.")))
            return
        }
        _action.value = ActionUi(key, action, target, ActionState())
        viewModelScope.launch {
            start(socket).collect { _action.value = ActionUi(key, action, target, it) }
        }
    }

    /** Clears a finished action. A running one cannot be dismissed. */
    fun dismissAction() {
        if (_action.value?.state?.running == false) _action.value = null
    }

    /** Read-only: service status and container list of one stack on one server ("" = the instance itself). */
    suspend fun loadDetail(instanceId: String, endpoint: String, stackName: String): Result<StackDetail> {
        val session = container.sessions.session(instanceId)
            ?: return Result.failure(IllegalStateException("This instance is not connected."))
        return StackDetailLoader.load(session.socket, stackName, endpoint)
    }
}
