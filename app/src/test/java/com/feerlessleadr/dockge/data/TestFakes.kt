package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.api.SocketState
import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.core.InstanceStore
import com.feerlessleadr.dockge.core.TokenVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement

internal class FakeSocket : DockgeSocket {
    val stateFlow = MutableStateFlow<SocketState>(SocketState.Disconnected)
    override val state: StateFlow<SocketState> = stateFlow
    val calls = mutableListOf<Pair<String, List<JsonElement>>>()
    var handler: (String, List<JsonElement>) -> JsonElement = { e, _ -> error("unexpected call $e") }
    var autoConnect = true
    var closed = false
    private var generation = 0

    override fun connect() {
        stateFlow.value = SocketState.Connecting
        if (autoConnect) stateFlow.value = SocketState.Connected(++generation)
    }

    override fun close() {
        closed = true
        stateFlow.value = SocketState.Disconnected
    }

    override suspend fun call(event: String, args: List<JsonElement>, timeoutMs: Long): JsonElement {
        calls += event to args
        return handler(event, args)
    }

    override fun events(event: String): Flow<List<JsonElement>> = emptyFlow()

    fun reconnect() {
        stateFlow.value = SocketState.Disconnected
        stateFlow.value = SocketState.Connected(++generation)
    }
}

internal class MemoryVault : TokenVault {
    val map = mutableMapOf<String, String>()
    override fun get(instanceId: String) = map[instanceId]
    override fun put(instanceId: String, token: String) { map[instanceId] = token }
    override fun remove(instanceId: String) { map.remove(instanceId) }
}

internal class MemoryInstanceStore(var saved: List<Instance> = emptyList()) : InstanceStore {
    var saves = 0
    override fun load(): List<Instance> = saved
    override fun save(instances: List<Instance>) { saved = instances; saves++ }
}
