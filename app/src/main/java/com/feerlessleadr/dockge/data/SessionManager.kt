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
 * Keeps one [InstanceSession] (one socket, one token) and one [StackListStore] per saved instance and follows the
 * repository: new instances connect, deleted ones disconnect, and an instance whose address changed gets a fresh
 * socket. Everything runs on [scope], which must be single threaded (the main dispatcher in the app).
 */
class SessionManager(
    private val repository: InstanceRepository,
    private val vault: TokenVault,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val socketFactory: (BaseUrl) -> DockgeSocket,
) {
    private class Entry(
        val baseUrl: String,
        val session: InstanceSession,
        val stackStore: StackListStore,
        val collectors: List<Job>,
        val openedAt: Long,
    )

    private val entries = mutableMapOf<String, Entry>()
    private val logs = mutableMapOf<String, EventLog>()
    private var followJob: Job? = null

    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())

    /** Connection state per instance id. */
    val states: StateFlow<Map<String, ConnectionState>> = _states.asStateFlow()

    private val _stackLists = MutableStateFlow<Map<String, List<ServerStacks>>>(emptyMap())

    /** Last received stack lists per instance id, one entry per server (the instance itself and any linked servers). Absent until the first push. */
    val stackLists: StateFlow<Map<String, List<ServerStacks>>> = _stackLists.asStateFlow()

    fun start() {
        if (followJob != null) return
        followJob = scope.launch { repository.instances.collect(::sync) }
    }

    fun session(id: String): InstanceSession? = entries[id]?.session

    /** The diagnostics log of one instance; it survives reconnects and is dropped when the instance is deleted. */
    fun log(id: String): EventLog? = logs[id]

    /**
     * Call when the app comes to the foreground or the network comes back. A phone that slept can leave sockets half
     * dead, so instances that are not connected get a fresh socket, and connected ones are checked first.
     * Instances waiting for a password or code are left alone. A socket younger than [minAgeMs] is still trying and
     * is not restarted (so the app start does not open every connection twice).
     */
    fun recover(minAgeMs: Long = RECOVER_GRACE_MS) {
        for (id in entries.keys.toList()) {
            when (_states.value[id]) {
                ConnectionState.Connected -> scope.launch {
                    val session = entries[id]?.session ?: return@launch
                    if (!session.verify()) {
                        logs[id]?.add("recover: connection did not answer, rebuilding")
                        reconnect(id)
                    }
                }
                is ConnectionState.NeedsLogin, is ConnectionState.NeedsTwoFactor, ConnectionState.LoggingIn -> Unit
                else -> {
                    val opened = entries[id]?.openedAt ?: continue
                    if (clock() - opened >= minAgeMs) {
                        logs[id]?.add("recover: not connected, rebuilding")
                        reconnect(id)
                    }
                }
            }
        }
    }

    /** Drops the socket and connects again (the "Retry" action). */
    fun reconnect(id: String) {
        val instance = repository.get(id) ?: return
        remove(id)
        open(instance)
    }

    private companion object {
        const val RECOVER_GRACE_MS = 10_000L
    }

    private fun sync(list: List<Instance>) {
        val ids = list.map { it.id }.toSet()
        entries.keys.filter { it !in ids }.forEach(::remove)
        logs.keys.filter { it !in ids }.forEach(logs::remove)
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
        val log = logs.getOrPut(instance.id) { EventLog() }
        log.add("socket opened")
        val socket = socketFactory(url)
        // The store listens before the session starts: the server pushes the first list right after login.
        val store = StackListStore(socket, scope, log)
        val session = InstanceSession(instance.id, socket, vault, scope)
        val collectors = listOf(
            scope.launch {
                session.state.collect { s ->
                    log.add("state: $s")
                    _states.update { it + (instance.id to s) }
                }
            },
            scope.launch { store.servers.collect { list -> if (list != null) _stackLists.update { it + (instance.id to list) } } },
        )
        entries[instance.id] = Entry(instance.baseUrl, session, store, collectors, clock())
        session.start()
    }

    private fun remove(id: String) {
        entries.remove(id)?.let {
            it.collectors.forEach(Job::cancel)
            it.stackStore.stop()
            it.session.stop()
        }
        _states.update { it - id }
        _stackLists.update { it - id }
    }
}
