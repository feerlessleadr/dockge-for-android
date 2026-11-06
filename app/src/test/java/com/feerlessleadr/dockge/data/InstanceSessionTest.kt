package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.CallTimeoutException
import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.api.FailureKind
import com.feerlessleadr.dockge.api.SocketState
import com.feerlessleadr.dockge.core.TokenVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSocket : DockgeSocket {
    val stateFlow = MutableStateFlow<SocketState>(SocketState.Disconnected)
    override val state: StateFlow<SocketState> = stateFlow
    val calls = mutableListOf<Pair<String, List<JsonElement>>>()
    var handler: (String, List<JsonElement>) -> JsonElement = { e, _ -> error("unexpected call $e") }
    var autoConnect = true
    private var generation = 0

    override fun connect() {
        stateFlow.value = SocketState.Connecting
        if (autoConnect) stateFlow.value = SocketState.Connected(++generation)
    }

    override fun close() { stateFlow.value = SocketState.Disconnected }

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

private class MemoryVault : TokenVault {
    val map = mutableMapOf<String, String>()
    override fun get(instanceId: String) = map[instanceId]
    override fun put(instanceId: String, token: String) { map[instanceId] = token }
    override fun remove(instanceId: String) { map.remove(instanceId) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InstanceSessionTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    private fun field(arg: JsonElement, name: String) = (arg as JsonObject)[name]?.jsonPrimitive?.content

    private class Rig(scope: TestScope) {
        val socket = FakeSocket()
        val vault = MemoryVault()
        val session = InstanceSession("i1", socket, vault, scope.backgroundScope)
    }

    @Test fun noTokenAsksForLogin() = runTest {
        val r = Rig(this)
        r.session.start(); runCurrent()
        assertEquals(ConnectionState.NeedsLogin(null), r.session.state.value)
        assertTrue(r.socket.calls.isEmpty())
    }

    @Test fun storedTokenLogsInOnConnect() = runTest {
        val r = Rig(this)
        r.vault.put("i1", "T")
        r.socket.handler = { e, a -> assertEquals("loginByToken", e); assertEquals("T", a[0].jsonPrimitive.content); json("""{"ok":true}""") }
        r.session.start(); runCurrent()
        assertEquals(ConnectionState.Connected, r.session.state.value)
    }

    @Test fun revokedTokenIsDeletedAndLoginRequested() = runTest {
        val r = Rig(this)
        r.vault.put("i1", "OLD")
        r.socket.handler = { _, _ -> json("""{"ok":false,"msg":"authInvalidToken","msgi18n":true}""") }
        r.session.start(); runCurrent()
        assertEquals(ConnectionState.NeedsLogin(FailureKind.Revoked), r.session.state.value)
        assertNull(r.vault.get("i1"))
    }

    @Test fun timeoutKeepsTheToken() = runTest {
        val r = Rig(this)
        r.vault.put("i1", "T")
        r.socket.handler = { e, _ -> throw CallTimeoutException(e) }
        r.session.start(); runCurrent()
        assertTrue(r.session.state.value is ConnectionState.Failed)
        assertEquals("T", r.vault.get("i1"))
    }

    @Test fun reconnectLogsInAgainWithTheToken() = runTest {
        val r = Rig(this)
        r.vault.put("i1", "T")
        r.socket.handler = { _, _ -> json("""{"ok":true}""") }
        r.session.start(); runCurrent()
        r.socket.reconnect(); runCurrent()
        assertEquals(2, r.socket.calls.count { it.first == "loginByToken" })
        assertEquals(ConnectionState.Connected, r.session.state.value)
    }

    @Test fun passwordLoginStoresOnlyTheToken() = runTest {
        val r = Rig(this)
        r.socket.handler = { e, a ->
            assertEquals("login", e)
            assertEquals("alice", field(a[0], "username"))
            assertNull(field(a[0], "token"))
            json("""{"ok":true,"token":"JWT1"}""")
        }
        r.session.start(); runCurrent()
        r.session.submitCredentials("alice", "pw"); runCurrent()
        assertEquals(ConnectionState.Connected, r.session.state.value)
        assertEquals("JWT1", r.vault.get("i1"))
        assertEquals(setOf("i1"), r.vault.map.keys)
        assertTrue("no password in the vault", r.vault.map.values.none { it.contains("pw") })
    }

    @Test fun wrongPasswordShowsReason() = runTest {
        val r = Rig(this)
        r.socket.handler = { _, _ -> json("""{"ok":false,"msg":"authIncorrectCreds","msgi18n":true}""") }
        r.session.start(); runCurrent()
        r.session.submitCredentials("alice", "bad"); runCurrent()
        assertEquals(ConnectionState.NeedsLogin(FailureKind.InvalidCredentials), r.session.state.value)
        assertNull(r.vault.get("i1"))
    }

    @Test fun rateLimitIsReportedNotRetried() = runTest {
        val r = Rig(this)
        r.socket.handler = { _, _ -> json("""{"ok":false,"msg":"Too frequently, try again later."}""") }
        r.session.start(); runCurrent()
        r.session.submitCredentials("alice", "pw"); runCurrent()
        assertEquals(ConnectionState.NeedsLogin(FailureKind.RateLimited), r.session.state.value)
        assertEquals(1, r.socket.calls.size)
    }

    @Test fun twoFactorFlowWithOneWrongCode() = runTest {
        val r = Rig(this)
        r.socket.handler = { _, a ->
            when {
                field(a[0], "token") == null -> json("""{"tokenRequired":true,"challenge":"CH"}""")
                field(a[0], "token") == "111111" -> json("""{"ok":false,"msg":"twoFactor.invalidCode","msgi18n":true}""")
                else -> {
                    assertEquals("CH", field(a[0], "challenge"))
                    assertEquals("alice", field(a[0], "username"))
                    json("""{"ok":true,"token":"JWT2"}""")
                }
            }
        }
        r.session.start(); runCurrent()
        r.session.submitCredentials("alice", "pw"); runCurrent()
        assertEquals(ConnectionState.NeedsTwoFactor(null), r.session.state.value)
        r.session.submitCode("111111"); runCurrent()
        assertEquals(ConnectionState.NeedsTwoFactor(FailureKind.InvalidCode), r.session.state.value)
        assertNull(r.vault.get("i1"))
        r.session.submitCode("222222"); runCurrent()
        assertEquals(ConnectionState.Connected, r.session.state.value)
        assertEquals("JWT2", r.vault.get("i1"))
    }

    @Test fun expiredChallengeGoesBackToPassword() = runTest {
        val r = Rig(this)
        r.socket.handler = { _, a ->
            if (field(a[0], "token") == null) json("""{"tokenRequired":true,"challenge":"CH"}""")
            else json("""{"ok":false,"msg":"twoFactor.restartLogin","msgi18n":true}""")
        }
        r.session.start(); runCurrent()
        r.session.submitCredentials("alice", "pw"); runCurrent()
        r.session.submitCode("123456"); runCurrent()
        assertEquals(ConnectionState.NeedsLogin(FailureKind.ChallengeExpired), r.session.state.value)
    }

    @Test fun dropDuringCodeStepForgetsThePassword() = runTest {
        val r = Rig(this)
        r.socket.handler = { _, _ -> json("""{"tokenRequired":true,"challenge":"CH"}""") }
        r.session.start(); runCurrent()
        r.session.submitCredentials("alice", "pw"); runCurrent()
        r.socket.reconnect(); runCurrent()
        assertEquals(ConnectionState.NeedsLogin(null), r.session.state.value)
        r.session.submitCode("123456"); runCurrent()
        assertEquals("a code without a pending challenge sends nothing", 1, r.socket.calls.size)
    }

    @Test fun signOutDeletesTheToken() = runTest {
        val r = Rig(this)
        r.vault.put("i1", "T")
        r.socket.handler = { _, _ -> json("""{"ok":true}""") }
        r.session.start(); runCurrent()
        r.session.signOut(); runCurrent()
        assertNull(r.vault.get("i1"))
        assertEquals(ConnectionState.Disconnected, r.session.state.value)
    }
}
