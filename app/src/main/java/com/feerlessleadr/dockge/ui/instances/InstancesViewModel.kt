package com.feerlessleadr.dockge.ui.instances

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.feerlessleadr.dockge.DockgeApp
import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.data.ConnectionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class InstanceRow(val instance: Instance, val state: ConnectionState)

class InstancesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as DockgeApp).container

    val rows: StateFlow<List<InstanceRow>> = combine(container.instances.instances, container.sessions.states) { list, states ->
        list.map { InstanceRow(it, states[it.id] ?: ConnectionState.Connecting) }
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
}
