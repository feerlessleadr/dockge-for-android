package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.api.SelfUpdateApi
import com.feerlessleadr.dockge.api.SelfUpdateClient
import com.feerlessleadr.dockge.api.SelfUpdatePolicy
import com.feerlessleadr.dockge.core.BaseUrl
import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.core.TokenVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.feerlessleadr.dockge.api.ServerUpdates
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    private val selfClient: SelfUpdateApi = SelfUpdateClient(),
    private val socketFactory: (BaseUrl) -> DockgeSocket,
) {
    private class Entry(
        val baseUrl: String,
        val session: InstanceSession,
        val stackStore: StackListStore,
        val updateStore: UpdateStatusStore,
        val collectors: List<Job>,
        val openedAt: Long,
    )

    private val entries = mutableMapOf<String, Entry>()
    private val logs = mutableMapOf<String, EventLog>()
    private val selfWatch = mutableMapOf<String, Job>()
    private var followJob: Job? = null

    private val _selfUpdate = MutableStateFlow<Map<String, SelfUpdateState>>(emptyMap())

    /** The Dockge-Enhanced update state per instance id. Absent until the first status request. */
    val selfUpdate: StateFlow<Map<String, SelfUpdateState>> = _selfUpdate.asStateFlow()

    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())

    /** Connection state per instance id. */
    val states: StateFlow<Map<String, ConnectionState>> = _states.asStateFlow()

    private val _stackLists = MutableStateFlow<Map<String, List<ServerStacks>>>(emptyMap())

    /** Last received stack lists per instance id, one entry per server (the instance itself and any linked servers). Absent until the first push. */
    val stackLists: StateFlow<Map<String, List<ServerStacks>>> = _stackLists.asStateFlow()

    private val _updates = MutableStateFlow<Map<String, Map<String, ServerUpdates>>>(emptyMap())

    /** The image watcher's result per instance id and server endpoint. Absent until the first successful refresh. */
    val updates: StateFlow<Map<String, Map<String, ServerUpdates>>> = _updates.asStateFlow()

    /** Asks every online server of one instance for its update status now (read only). */
    suspend fun refreshUpdates(id: String) {
        val entry = entries[id] ?: return
        val servers = entry.stackStore.servers.value ?: return
        entry.updateStore.refresh(servers.filter { it.status != "offline" }.map { it.endpoint })
        if (entries[id] === entry) _updates.update { it + (id to entry.updateStore.byServer.value) }
        refreshSelfUpdate(id)
    }

    /**
     * Reads the Dockge-Enhanced update status (admin token, read only). A failed request keeps the last status and
     * marks the instance unreachable, which is the normal case while Dockge restarts during its own update.
     */
    suspend fun refreshSelfUpdate(id: String) {
        val entry = entries[id] ?: return
        val token = vault.get(id)
        if (token == null) {
            setSelf(id) { it.copy(reachable = false, error = "Sign in to check Dockge's update.") }
            return
        }
        try {
            val status = selfClient.status(entry.baseUrl, token)
            setSelf(id) { it.copy(status = status, reachable = true, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logs[id]?.add("self-update status failed: ${e.message}")
            setSelf(id) { it.copy(reachable = false, error = e.message ?: "Could not reach Dockge.") }
        }
    }

    /**
     * Starts the safe self-update to the image the server reported (its own backup, health check and rollback do the
     * work). The start call may take minutes or may never answer, because Dockge restarts during the update, so a
     * watch polls the status until the server reports an end. A second start is refused while one is watched.
     */
    fun startSelfUpdate(id: String) {
        val entry = entries[id]
        val state = _selfUpdate.value[id] ?: SelfUpdateState()
        val token = vault.get(id)
        val target = state.status?.let { SelfUpdatePolicy.targetImage(it) }
        val refusal = when {
            entry == null -> "This instance is not connected."
            token == null -> "Sign in to update Dockge."
            target == null -> "No Dockge update is available."
            selfWatch[id]?.isActive == true -> "A Dockge update is already running."
            else -> null
        }
        if (refusal != null || entry == null || token == null || target == null) {
            setSelf(id) { it.copy(error = refusal) }
            return
        }
        logs[id]?.add("self-update requested: $target")
        setSelf(id) { it.copy(watching = true, error = null) }
        selfWatch[id] = scope.launch {
            var startRefusal: String? = null
            val start = launch {
                try {
                    val operation = selfClient.start(entry.baseUrl, token, target)
                    logs[id]?.add("self-update start answered: ${operation.state}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Dockge restarting also cuts this call; the status watch below decides the outcome.
                    logs[id]?.add("self-update start call ended: ${e.message}")
                    startRefusal = e.message
                }
            }
            val deadline = clock() + SELF_UPDATE_WATCH_MS
            var sawActive = false
            var lastLogged = ""
            while (clock() < deadline && entries.containsKey(id)) {
                refreshSelfUpdate(id)
                val operation = _selfUpdate.value[id]?.status?.operation
                if (operation != null && operation.state != lastLogged) {
                    logs[id]?.add("self-update state: ${operation.state}")
                    lastLogged = operation.state
                }
                if (SelfUpdatePolicy.isActive(operation)) sawActive = true
                // The server refused the start (for example another update is running) and nothing is in progress.
                if (start.isCompleted && !sawActive && startRefusal != null) {
                    setSelf(id) { it.copy(error = startRefusal) }
                    break
                }
                // A finished state only counts once this run was seen, or the start call has answered (an old result is not news).
                if (SelfUpdatePolicy.isFinished(operation) && (sawActive || start.isCompleted)) break
                delay(SELF_UPDATE_POLL_MS)
            }
            setSelf(id) { it.copy(watching = false) }
        }
    }

    private fun setSelf(id: String, change: (SelfUpdateState) -> SelfUpdateState) {
        if (!entries.containsKey(id)) return
        _selfUpdate.update { it + (id to change(it[id] ?: SelfUpdateState())) }
    }

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
                    } else {
                        // Back in the app: the watcher may have found something since the last 5 minute round.
                        refreshUpdates(id)
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

        /** Polls while Dockge restarts; a failed poll is cheap. */
        const val SELF_UPDATE_POLL_MS = 2_000L

        /** A backup of a large data directory can take a long time before the update starts. */
        const val SELF_UPDATE_WATCH_MS = 2 * 60 * 60_000L

        /** The server's own scan runs every 6 h by default, so asking more often than this gains nothing. */
        const val UPDATE_REFRESH_MS = 5 * 60_000L
    }

    private fun sync(list: List<Instance>) {
        val ids = list.map { it.id }.toSet()
        entries.keys.filter { it !in ids }.forEach(::remove)
        logs.keys.filter { it !in ids }.forEach(logs::remove)
        selfWatch.keys.filter { it !in ids }.forEach { selfWatch.remove(it)?.cancel() }
        _selfUpdate.update { state -> state.filterKeys { it in ids } }
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
        val updateStore = UpdateStatusStore(socket, log)
        val session = InstanceSession(instance.id, socket, vault, scope)
        val collectors = listOf(
            scope.launch {
                session.state.collect { s ->
                    log.add("state: $s")
                    _states.update { it + (instance.id to s) }
                }
            },
            scope.launch { store.servers.collect { list -> if (list != null) _stackLists.update { it + (instance.id to list) } } },
            // While connected: ask for update status once the server list is known, then every few minutes.
            scope.launch {
                session.state.collectLatest { s ->
                    if (s == ConnectionState.Connected) {
                        store.servers.filterNotNull().first()
                        while (true) {
                            refreshUpdates(instance.id)
                            delay(UPDATE_REFRESH_MS)
                        }
                    }
                }
            },
        )
        entries[instance.id] = Entry(instance.baseUrl, session, store, updateStore, collectors, clock())
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
        _updates.update { it - id }
    }
}
