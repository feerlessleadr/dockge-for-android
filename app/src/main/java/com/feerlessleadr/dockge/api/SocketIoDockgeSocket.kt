package com.feerlessleadr.dockge.api

import com.feerlessleadr.dockge.core.BaseUrl
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.coroutines.resume

/**
 * Socket.IO over OkHttp WebSocket. No Origin and no "endpoint" header are sent: the server skips its origin check
 * when there is no Origin, and an endpoint header would mark the socket as a federated agent.
 * The library reconnects by itself; the session must log in again on every [SocketState.Connected].
 */
class SocketIoDockgeSocket(baseUrl: BaseUrl) : DockgeSocket {
    private val socket: Socket = IO.socket(
        // The URI path would be read as a Socket.IO namespace, so only the origin goes here.
        URI.create(baseUrl.origin),
        IO.Options().apply {
            path = baseUrl.socketPath
            transports = arrayOf(WebSocket.NAME)
            forceNew = true
            reconnection = true
            reconnectionDelay = 1_000
            reconnectionDelayMax = 30_000
            timeout = 15_000
        },
    )

    private val _state = MutableStateFlow<SocketState>(SocketState.Disconnected)
    override val state: StateFlow<SocketState> = _state.asStateFlow()
    private var generation = 0

    init {
        socket.on(Socket.EVENT_CONNECT) { _state.value = SocketState.Connected(++generation) }
        socket.on(Socket.EVENT_DISCONNECT) { _state.value = SocketState.Disconnected }
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val reason = (args.firstOrNull() as? Throwable)?.message ?: args.firstOrNull()?.toString() ?: "Connection failed"
            _state.value = SocketState.Error(reason)
        }
    }

    override fun connect() {
        _state.value = SocketState.Connecting
        socket.connect()
    }

    override fun close() {
        socket.off()
        socket.disconnect()
        _state.value = SocketState.Disconnected
    }

    override suspend fun call(event: String, args: List<JsonElement>, timeoutMs: Long): JsonElement {
        if (!socket.connected()) throw NotConnectedException()
        val reply = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val ack = Ack { reply ->
                    if (cont.isActive) cont.resume(fromJava(reply.firstOrNull()))
                }
                socket.emit(event, args.map(::toJava).toTypedArray(), ack)
            }
        }
        return reply ?: throw CallTimeoutException(event)
    }

    override fun events(event: String): Flow<List<JsonElement>> = callbackFlow {
        val listener = Emitter.Listener { args -> trySend(args.map(::fromJava)) }
        socket.on(event, listener)
        awaitClose { socket.off(event, listener) }
    }

    private fun toJava(element: JsonElement): Any = when (element) {
        is JsonNull -> JSONObject.NULL
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull!!
            element.longOrNull != null -> element.longOrNull!!
            else -> element.doubleOrNull ?: element.content
        }
        is JsonObject -> JSONObject(element.toString())
        is JsonArray -> JSONArray(element.toString())
    }

    private fun fromJava(value: Any?): JsonElement = when (value) {
        null, JSONObject.NULL -> JsonNull
        is JSONObject -> Json.parseToJsonElement(value.toString())
        is JSONArray -> Json.parseToJsonElement(value.toString())
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
