package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.CallTimeoutException
import com.feerlessleadr.dockge.core.BaseUrl
import com.feerlessleadr.dockge.core.InstanceColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/** The phone sleeps, the connection dies half way, and the app must come back without being restarted. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryTest {
    private fun ok() = Json.parseToJsonElement("""{"ok":true}""")

    @Test fun tokenLoginThatTimesOutIsRetriedUntilItWorks() = runTest {
        val socket = FakeSocket()
        val vault = MemoryVault().apply { put("i1", "T") }
        var failures = 2
        socket.handler = { e, _ -> if (failures-- > 0) throw CallTimeoutException(e) else ok() }
        val session = InstanceSession("i1", socket, vault, backgroundScope)
        session.start(); runCurrent()
        assertTrue(session.state.value is ConnectionState.Failed)
        advanceTimeBy(2_001); runCurrent()
        assertTrue("second attempt also times out", session.state.value is ConnectionState.Failed)
        advanceTimeBy(4_001); runCurrent()
        assertEquals(ConnectionState.Connected, session.state.value)
        assertEquals(3, socket.calls.size)
        assertEquals("T", vault.get("i1"))
    }

    @Test fun retriesStopAfterAFewAttempts() = runTest {
        val socket = FakeSocket()
        val vault = MemoryVault().apply { put("i1", "T") }
        socket.handler = { e, _ -> throw CallTimeoutException(e) }
        val session = InstanceSession("i1", socket, vault, backgroundScope)
        session.start(); runCurrent()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(7, socket.calls.size)
        assertTrue(session.state.value is ConnectionState.Failed)
        assertEquals("the token is kept", "T", vault.get("i1"))
    }

    @Test fun reconnectCancelsAPendingRetry() = runTest {
        val socket = FakeSocket()
        val vault = MemoryVault().apply { put("i1", "T") }
        var fail = true
        socket.handler = { e, _ -> if (fail) throw CallTimeoutException(e) else ok() }
        val session = InstanceSession("i1", socket, vault, backgroundScope)
        session.start(); runCurrent()
        fail = false
        socket.reconnect(); runCurrent()
        assertEquals(ConnectionState.Connected, session.state.value)
        val callsAfterReconnect = socket.calls.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals("no stray retry after success", callsAfterReconnect, socket.calls.size)
    }

    @Test fun verifyFailsOnTimeoutAndSucceedsWhenAnswered() = runTest {
        val socket = FakeSocket()
        val vault = MemoryVault().apply { put("i1", "T") }
        socket.handler = { _, _ -> ok() }
        val session = InstanceSession("i1", socket, vault, backgroundScope)
        session.start(); runCurrent()
        assertTrue(session.verify())
        socket.handler = { e, _ -> throw CallTimeoutException(e) }
        assertFalse(session.verify())
        assertTrue("no token means nothing to verify", InstanceSession("none", socket, vault, backgroundScope).verify())
    }

    private class Rig(scope: kotlinx.coroutines.test.TestScope) {
        val vault = MemoryVault()
        val repo = InstanceRepository(MemoryInstanceStore(), vault)
        val sockets = mutableListOf<FakeSocket>()
        var now = 1_000_000L
        var handler: (String) -> Unit = {}
        val manager = SessionManager(repo, vault, scope.backgroundScope, clock = { now }) { _: BaseUrl ->
            FakeSocket().also {
                it.handler = { e, _ -> handler(e); Json.parseToJsonElement("""{"ok":true}""") }
                sockets += it
            }
        }
    }

    @Test fun recoverRebuildsAFailedInstanceButNotOneOpenedJustNow() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", InstanceColors.palette[0]).getOrThrow()
        r.vault.put(a.id, "T")
        r.handler = { throw CallTimeoutException(it) }
        r.manager.start(); runCurrent()
        assertTrue(r.manager.states.value[a.id] is ConnectionState.Failed)
        r.manager.recover(); runCurrent()
        assertEquals("opened a moment ago: left alone", 1, r.sockets.size)
        r.now += 11_000
        r.handler = {}
        r.manager.recover(); runCurrent()
        assertEquals(2, r.sockets.size)
        assertEquals(ConnectionState.Connected, r.manager.states.value[a.id])
    }

    @Test fun recoverChecksAConnectedInstanceAndRebuildsADeadOne() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", InstanceColors.palette[0]).getOrThrow()
        r.vault.put(a.id, "T")
        r.manager.start(); runCurrent()
        assertEquals(ConnectionState.Connected, r.manager.states.value[a.id])
        r.manager.recover(); runCurrent()
        assertEquals("healthy connection is kept", 1, r.sockets.size)
        r.handler = { throw CallTimeoutException(it) }
        r.manager.recover(); runCurrent()
        assertEquals("a connection that does not answer is rebuilt", 2, r.sockets.size)
        assertTrue(r.sockets.first().closed)
    }

    @Test fun recoverLeavesInstancesWaitingForALoginAlone() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", InstanceColors.palette[0]).getOrThrow()
        r.manager.start(); runCurrent()
        assertEquals(ConnectionState.NeedsLogin(null), r.manager.states.value[a.id])
        r.now += 60_000
        r.manager.recover(); runCurrent()
        assertEquals(1, r.sockets.size)
    }
}
