package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.core.BaseUrl
import com.feerlessleadr.dockge.core.InstanceColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
    private val blue = InstanceColors.palette[0]

    private class Rig(scope: TestScope) {
        val vault = MemoryVault()
        val repo = InstanceRepository(MemoryInstanceStore(), vault)
        val sockets = mutableListOf<Pair<BaseUrl, FakeSocket>>()
        val manager = SessionManager(repo, vault, scope.backgroundScope) { url ->
            FakeSocket().also {
                it.handler = { _, _ -> Json.parseToJsonElement("""{"ok":true}""") }
                sockets += url to it
            }
        }
    }

    @Test fun existingInstancesConnectOnStart() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(a.id, "T")
        r.manager.start(); runCurrent()
        assertEquals(ConnectionState.Connected, r.manager.states.value[a.id])
        assertEquals(1, r.sockets.size)
    }

    @Test fun instanceWithoutTokenNeedsLogin() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.manager.start(); runCurrent()
        assertEquals(ConnectionState.NeedsLogin(null), r.manager.states.value[a.id])
    }

    @Test fun twoInstancesAreIndependent() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        val b = r.repo.add("B", "https://b.example.com", blue).getOrThrow()
        r.vault.put(a.id, "TA")
        r.manager.start(); runCurrent()
        assertEquals(ConnectionState.Connected, r.manager.states.value[a.id])
        assertEquals(ConnectionState.NeedsLogin(null), r.manager.states.value[b.id])
        assertEquals(2, r.sockets.size)
    }

    @Test fun addingAnInstanceLaterConnectsIt() = runTest {
        val r = Rig(this)
        r.manager.start(); runCurrent()
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        runCurrent()
        assertEquals(ConnectionState.NeedsLogin(null), r.manager.states.value[a.id])
    }

    @Test fun deletingDisconnectsAndForgets() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.manager.start(); runCurrent()
        r.repo.delete(a.id); runCurrent()
        assertTrue(r.sockets.single().second.closed)
        assertNull(r.manager.states.value[a.id])
        assertNull(r.manager.session(a.id))
    }

    @Test fun renameKeepsTheConnection() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.manager.start(); runCurrent()
        val before = r.manager.session(a.id)
        r.repo.update(a.id, "Renamed", "https://a.example.com", blue); runCurrent()
        assertSame(before, r.manager.session(a.id))
        assertEquals(1, r.sockets.size)
    }

    @Test fun newAddressGetsANewSocketAndNoToken() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(a.id, "T")
        r.manager.start(); runCurrent()
        val before = r.manager.session(a.id)
        r.repo.update(a.id, "A", "https://moved.example.com", blue); runCurrent()
        assertNotSame(before, r.manager.session(a.id))
        assertEquals(2, r.sockets.size)
        assertEquals("https://moved.example.com", r.sockets.last().first.value)
        assertTrue(r.sockets.first().second.closed)
        assertEquals(ConnectionState.NeedsLogin(null), r.manager.states.value[a.id])
    }

    @Test fun reconnectRebuildsTheSession() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.manager.start(); runCurrent()
        r.manager.reconnect(a.id); runCurrent()
        assertEquals(2, r.sockets.size)
        assertNotNull(r.manager.session(a.id))
    }

    @Test fun signOutThenReconnectShowsLoginAgain() = runTest {
        val r = Rig(this)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(a.id, "T")
        r.manager.start(); runCurrent()
        r.manager.session(a.id)!!.signOut()
        r.manager.reconnect(a.id); runCurrent()
        assertNull(r.vault.get(a.id))
        assertEquals(ConnectionState.NeedsLogin(null), r.manager.states.value[a.id])
    }
}
