package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.CallTimeoutException
import com.feerlessleadr.dockge.api.SocketState
import com.feerlessleadr.dockge.core.TerminalBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StackActionTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun connectedSocket() = FakeSocket().also { it.connect() }

    private fun write(socket: FakeSocket, terminal: String, data: String) =
        socket.push("agent", JsonPrimitive("terminalWrite"), JsonPrimitive(terminal), JsonPrimitive(data))

    @Test fun sendsTheRightCallToTheRightServer() = runTest {
        val socket = connectedSocket()
        socket.handler = { _, _ -> j("""{"ok":true,"msg":"Stopped"}""") }
        val last = StackActionRunner.run(socket, "whoami", "h2:5001", StackAction.Stop).last()
        assertEquals(ActionOutcome.Done, last.outcome)
        val (event, args) = socket.calls.single()
        assertEquals("agent", event)
        assertEquals(listOf("h2:5001", "stopStack", "whoami"), args.map { it.jsonPrimitive.content })
    }

    @Test fun containerActionSendsTheContainerCallToTheRightServer() = runTest {
        val socket = connectedSocket()
        socket.handler = { _, _ -> j("""{"ok":true}""") }
        val last = StackActionRunner.runContainer(socket, "whoami", "abcdef123456", "h2:5001", ContainerAction.Restart).last()
        assertEquals(ActionOutcome.Done, last.outcome)
        assertEquals(
            listOf("h2:5001", "containerInstanceAction", "whoami", "abcdef123456", "restart"),
            socket.calls.single().second.map { it.jsonPrimitive.content },
        )
    }

    @Test fun containerActionErrorsAreShown() = runTest {
        val socket = connectedSocket()
        socket.handler = { _, _ -> j("""{"ok":false,"msg":"use the service action instead"}""") }
        val last = StackActionRunner.runContainer(socket, "whoami", "abcdef123456", "", ContainerAction.Stop).last()
        assertEquals(ActionOutcome.Failed("use the service action instead"), last.outcome)
    }

    @Test fun containerStopAndRestartAskFirstButStartDoesNot() {
        assertEquals(null, ContainerAction.Start.warning)
        assertTrue(ContainerAction.Stop.warning != null && ContainerAction.Restart.warning != null)
    }

    @Test fun everyActionMapsToItsDockgeEvent() {
        assertEquals(
            listOf("startStack", "restartStack", "stopStack", "downStack", "updateStack"),
            StackAction.entries.map { it.event },
        )
        assertTrue("Update always asks first", StackAction.Update.warning != null)
        assertEquals("Start has no confirm dialog", null, StackAction.Start.warning)
        assertTrue(StackAction.entries.filter { it != StackAction.Start }.all { it.warning != null })
    }

    @Test fun collectsOutputOfThisStacksTerminalOnly() = runTest {
        val socket = connectedSocket()
        socket.handler = { _, _ ->
            write(socket, "compose--whoami", "Container whoami-1  Stopping\r\n")
            write(socket, "compose--other", "not mine\r\n")
            write(socket, "combined--whoami", "log line\r\n")
            write(socket, "compose--whoami", "Container whoami-1  Stopped\r\n")
            j("""{"ok":true}""")
        }
        val last = StackActionRunner.run(socket, "whoami", "", StackAction.Stop).last()
        assertEquals("Container whoami-1  Stopping\nContainer whoami-1  Stopped\n", last.output)
        assertEquals(ActionOutcome.Done, last.outcome)
    }

    @Test fun linkedServerTerminalNameIsAccepted() = runTest {
        val socket = connectedSocket()
        socket.handler = { _, _ ->
            write(socket, "compose-h2:5001-web", "a\n")
            j("""{"ok":true}""")
        }
        assertEquals("a\n", StackActionRunner.run(socket, "web", "h2:5001", StackAction.Start).last().output)
    }

    @Test fun updateIsAcknowledgedAsRollingUpdateWhenTheServerSaysPending() = runTest {
        val socket = connectedSocket()
        socket.handler = { _, _ -> j("""{"ok":true,"msg":"serviceRollout.pending","msgi18n":true}""") }
        val last = StackActionRunner.run(socket, "web", "", StackAction.Update).last()
        assertEquals(ActionOutcome.RolloutPending, last.outcome)
        assertEquals("updateStack", socket.calls.single().second[1].jsonPrimitive.content)
    }

    @Test fun updateRefusedWhileAnotherUpdateRunsShowsTheServersReason() = runTest {
        val socket = connectedSocket()
        socket.handler = { _, _ -> j("""{"ok":false,"msg":"Another Docker update is active; retry later"}""") }
        val last = StackActionRunner.run(socket, "web", "", StackAction.Update).last()
        assertEquals(ActionOutcome.Failed("Another Docker update is active; retry later"), last.outcome)
    }

    @Test fun serverErrorBecomesAFailure() = runTest {
        val socket = connectedSocket()
        socket.handler = { _, _ -> j("""{"ok":false,"msg":"Failed to start, please check the terminal output for more information."}""") }
        val last = StackActionRunner.run(socket, "whoami", "", StackAction.Start).last()
        assertEquals(
            ActionOutcome.Failed("Failed to start, please check the terminal output for more information."),
            last.outcome,
        )
    }

    @Test fun noAnswerBecomesAFailureThatWarnsTheActionMayStillRun() = runTest {
        val socket = connectedSocket()
        socket.handler = { e, _ -> throw CallTimeoutException(e) }
        val outcome = StackActionRunner.run(socket, "whoami", "", StackAction.Restart).last().outcome
        assertTrue(outcome is ActionOutcome.Failed)
        assertTrue((outcome as ActionOutcome.Failed).message.contains("may still be running"))
    }

    @Test fun losingTheConnectionEndsTheActionAndSaysSo() = runTest {
        val socket = connectedSocket()
        val never = CompletableDeferred<JsonElement>()
        socket.suspendHandler = { _, _ ->
            socket.stateFlow.value = SocketState.Disconnected
            never.await()
        }
        val outcome = StackActionRunner.run(socket, "whoami", "", StackAction.Down).last().outcome
        assertTrue(outcome is ActionOutcome.Failed)
        assertTrue((outcome as ActionOutcome.Failed).message.contains("connection was lost"))
    }

    @Test fun notConnectedFailsWithoutSendingAnything() = runTest {
        val socket = FakeSocket()
        val states = StackActionRunner.run(socket, "whoami", "", StackAction.Start).toList()
        assertEquals(ActionOutcome.Failed("Not connected."), states.single().outcome)
        assertTrue(socket.calls.isEmpty())
    }

    @Test fun runningStateHasNoOutcome() = runTest {
        val socket = connectedSocket()
        socket.handler = { _, _ -> j("""{"ok":true}""") }
        val states = StackActionRunner.run(socket, "whoami", "", StackAction.Start).toList()
        assertTrue(states.first().running)
        assertFalse(states.last().running)
    }

    // --- TerminalBuffer ---

    @Test fun bufferStripsAnsiAndKeepsLines() {
        val b = TerminalBuffer()
        b.append("\u001B[32mDone\u001B[0m\r\nnext\r\n")
        assertEquals("Done\nnext\n", b.text())
    }

    @Test fun bufferLetsACarriageReturnReplaceTheLine() {
        val b = TerminalBuffer()
        b.append(" web Pulling 10%\r web Pulling 55%\r web Pulled\r\n")
        assertEquals(" web Pulled\n", b.text())
    }

    @Test fun bufferHandlesAnEscapeSequenceSplitAcrossChunks() {
        val b = TerminalBuffer()
        b.append("a\u001B[3")
        b.append("2mb\u001B")
        b.append("[0mc")
        assertEquals("abc", b.text())
    }

    @Test fun bufferDropsOtherControlCharactersAndKeepsTabs() {
        val b = TerminalBuffer()
        b.append("a\u0007\tb")
        assertEquals("a\tb", b.text())
    }

    @Test fun bufferKeepsOnlyTheTail() {
        val b = TerminalBuffer(maxChars = 10)
        b.append("0123456789ABCDEF")
        assertEquals("6789ABCDEF", b.text())
        b.append("\rXY")
        assertEquals("XY", b.text().takeLast(2))
    }
}
