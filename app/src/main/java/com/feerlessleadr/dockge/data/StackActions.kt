package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.api.SocketCallException
import com.feerlessleadr.dockge.api.SocketState
import com.feerlessleadr.dockge.api.agentCall
import com.feerlessleadr.dockge.core.TerminalBuffer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The stack actions of M4. "Start" is dockge's `startStack` (`docker compose up -d`), so there is no separate "up".
 * Updating is M5. [warning] is shown in the confirm dialog; null means no confirmation is asked.
 */
enum class StackAction(val event: String, override val label: String, override val doing: String, override val warning: String?) : Action {
    Start("startStack", "Start", "Starting", null),
    Restart("restartStack", "Restart", "Restarting", "All containers of this stack are restarted."),
    Stop("stopStack", "Stop", "Stopping", "The containers are stopped. Their data is kept."),
    Down("downStack", "Down", "Taking down", "The containers and the network of this stack are removed. Volumes and the compose files are kept."),
}

/** One container (`containerInstanceAction`: plain `docker start|stop|restart`, no terminal output). */
enum class ContainerAction(val verb: String, override val label: String, override val doing: String, override val warning: String?) : Action {
    Start("start", "Start", "Starting", null),
    Restart("restart", "Restart", "Restarting", "This container is restarted."),
    Stop("stop", "Stop", "Stopping", "This container is stopped. Its data is kept."),
}

interface Action {
    val label: String
    val doing: String
    /** Shown in the confirm dialog; null means no confirmation is asked. */
    val warning: String?
}

sealed interface ActionOutcome {
    data object Done : ActionOutcome
    data class Failed(val message: String) : ActionOutcome
}

/** [outcome] is null while the action runs. [output] is the server's terminal output so far, as plain text. */
data class ActionState(val output: String = "", val outcome: ActionOutcome? = null) {
    val running: Boolean get() = outcome == null
}

/**
 * Runs one write action and reports its terminal output and result. The server acknowledges only after the compose
 * command ended, may never acknowledge (another operation on the same stack), and a lost connection cannot tell us
 * how it ended, so the outcome is whichever comes first: the ack, the timeout, or the connection going away.
 */
object StackActionRunner {
    /** Pulling images can take minutes. */
    const val ACK_TIMEOUT_MS = 10 * 60_000L

    /** The server stops a plain container action after 30 s. */
    const val CONTAINER_TIMEOUT_MS = 60_000L

    fun run(
        socket: DockgeSocket,
        stackName: String,
        endpoint: String,
        action: StackAction,
        ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    ): Flow<ActionState> = execute(socket, action.event, listOf(JsonPrimitive(stackName)), endpoint, stackName, ackTimeoutMs)

    fun runContainer(
        socket: DockgeSocket,
        stackName: String,
        containerId: String,
        endpoint: String,
        action: ContainerAction,
        ackTimeoutMs: Long = CONTAINER_TIMEOUT_MS,
    ): Flow<ActionState> = execute(
        socket, "containerInstanceAction",
        listOf(JsonPrimitive(stackName), JsonPrimitive(containerId), JsonPrimitive(action.verb)), endpoint, stackName, ackTimeoutMs,
    )

    private fun execute(
        socket: DockgeSocket,
        event: String,
        args: List<JsonElement>,
        endpoint: String,
        stackName: String,
        ackTimeoutMs: Long,
    ): Flow<ActionState> = channelFlow {
        val connected = socket.state.value as? SocketState.Connected
        if (connected == null) {
            send(ActionState(outcome = ActionOutcome.Failed("Not connected.")))
            return@channelFlow
        }
        val buffer = TerminalBuffer()
        // The terminal of a stack action is named compose-<endpoint>-<stack>; a linked server may name it from its own point of view.
        val terminals = setOf("compose-$endpoint-$stackName", "compose--$stackName")
        send(ActionState())
        // Subscribe before emitting the call: output starts arriving before the ack.
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.events("agent").collect { args ->
                val kind = (args.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                val name = (args.getOrNull(1) as? JsonPrimitive)?.contentOrNull
                val data = (args.getOrNull(2) as? JsonPrimitive)?.contentOrNull
                if (kind == "terminalWrite" && name in terminals && data != null) {
                    buffer.append(data)
                    send(ActionState(buffer.text()))
                }
            }
        }
        val call = async {
            try {
                parseAck(socket.agentCall(event, args, ackTimeoutMs, endpoint))
            } catch (e: SocketCallException) {
                ActionOutcome.Failed(
                    if (e is com.feerlessleadr.dockge.api.CallTimeoutException) {
                        "No answer from the server. The action may still be running there: check the stack before trying again."
                    } else {
                        e.message ?: "The call failed."
                    },
                )
            }
        }
        val lost = async {
            socket.state.first { it != connected }
            ActionOutcome.Failed("The connection was lost. The server may still be running the action: check the stack before trying again.")
        }
        val outcome = select<ActionOutcome> {
            call.onAwait { it }
            lost.onAwait { it }
        }
        call.cancel()
        lost.cancel()
        // Output sent just before the ack may still be queued.
        yield()
        writer.cancel()
        send(ActionState(buffer.text(), outcome))
    }

    internal fun parseAck(reply: kotlinx.serialization.json.JsonElement): ActionOutcome {
        val obj = reply as? JsonObject ?: return ActionOutcome.Failed("Unexpected reply from the server.")
        if ((obj["ok"] as? JsonPrimitive)?.booleanOrNull == true) return ActionOutcome.Done
        return ActionOutcome.Failed((obj["msg"] as? JsonPrimitive)?.contentOrNull ?: "The server reported an error.")
    }
}
