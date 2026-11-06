package com.feerlessleadr.dockge.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

sealed interface SocketState {
    data object Disconnected : SocketState
    data object Connecting : SocketState

    /** [generation] grows on every (re)connect so observers never miss a reconnect. */
    data class Connected(val generation: Int) : SocketState

    data class Error(val message: String) : SocketState
}

/** Base for failures of a single call. The server sometimes never answers, so every call has a timeout. */
sealed class SocketCallException(message: String) : Exception(message)
class CallTimeoutException(event: String) : SocketCallException("No answer from the server for \"$event\".")
class NotConnectedException : SocketCallException("Not connected.")

/** The wire layer. Tests use a fake; production uses [SocketIoDockgeSocket]. Never log arguments: they can hold secrets. */
interface DockgeSocket {
    val state: StateFlow<SocketState>

    fun connect()
    fun close()

    /** Emits [event] with [args] and returns the first argument of the server's acknowledgement. */
    suspend fun call(event: String, args: List<JsonElement> = emptyList(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonElement

    /** Events pushed by the server, as the list of their arguments. */
    fun events(event: String): Flow<List<JsonElement>>

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

/** Every stack and container call is wrapped as emit("agent", endpoint, event, ...args). The endpoint "" is this instance. */
suspend fun DockgeSocket.agentCall(
    event: String,
    args: List<JsonElement> = emptyList(),
    timeoutMs: Long = DockgeSocket.DEFAULT_TIMEOUT_MS,
): JsonElement = call("agent", listOf(JsonPrimitive(""), JsonPrimitive(event)) + args, timeoutMs)
