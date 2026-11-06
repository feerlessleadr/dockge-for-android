package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.core.BaseUrl
import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.core.TokenVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps one [InstanceSession] (one socket, one token) per saved instance and follows the repository: new instances
 * connect, deleted ones disconnect, and an instance whose address changed gets a fresh socket.
 * Everything runs on [scope], which must be single threaded (the main dispatcher in the app).
 */
class SessionManager(
    private val repository: InstanceRepository,
    private val vault: TokenVault,
    private val scope: CoroutineScope,
    private val socketFactory: (BaseUrl) -> DockgeSocket,
) {
    private class Entry(val baseUrl: String, val session: InstanceSession, val collector: Job)

    private val entries = mutableMapOf<String, Entry>()
    private var followJob: Job? = null

    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())

    /** Connection state per instance id. */
    val states: StateFlow<Map<String, ConnectionState>> = _states.asStateFlow()

    fun start() {
        if (followJob != null) return
        followJob = scope.launch { repository.instances.collect(::sync) }
    }

    fun session(id: String): InstanceSession? = entries[id]?.session

    /** Drops the socket and connects again (the "Retry" action). */
    fun reconnect(id: String) {
        val instance = repository.get(id) ?: return
        remove(id)
        open(instance)
    }

    private fun sync(list: List<Instance>) {
        val ids = list.map { it.id }.toSet()
        entries.keys.filter { it !in ids }.forEach(::remove)
        for (instance in list) {
            val entry = entries[instance.id]
            when {
                entry == null -> open(instance)
                entry.baseUrl != instance.baseUrl -> {
                    remove(instance.id)
                    open(instance)
                }
            }
        }
    }

    private fun open(instance: Instance) {
        val url = BaseUrl.parse(instance.baseUrl, allowCleartext = true).getOrNull() ?: run {
            _states.update { it + (instance.id to ConnectionState.Failed("The saved address is not valid.")) }
            return
        }
        val session = InstanceSession(instance.id, socketFactory(url), vault, scope)
        val collector = scope.launch { session.state.collect { s -> _states.update { it + (instance.id to s) } } }
        entries[instance.id] = Entry(instance.baseUrl, session, collector)
        session.start()
    }

    private fun remove(id: String) {
        entries.remove(id)?.let {
            it.collector.cancel()
            it.session.stop()
        }
        _states.update { it - id }
    }
}
