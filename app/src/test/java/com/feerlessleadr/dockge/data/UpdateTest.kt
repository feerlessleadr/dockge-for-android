package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.CallTimeoutException
import com.feerlessleadr.dockge.api.ImageLinks
import com.feerlessleadr.dockge.api.StackParser
import com.feerlessleadr.dockge.core.InstanceColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private val statusReply = j(
        """{"ok":true,"data":[
          {"image":"nginx:latest","stack":"web","localDigest":"sha256:aaaaaaaaaaaaaaaa","remoteDigest":"sha256:bbbbbbbbbbbbbbbb","hasUpdate":true,"lastChecked":"2026-11-10T10:00:00.000Z"},
          {"image":"redis:7","stack":"web","localDigest":"sha256:c","remoteDigest":"sha256:c","hasUpdate":false,"lastChecked":"2026-11-10T10:00:00.000Z"},
          {"image":"db:1","stack":"data","localDigest":"sha256:d","remoteDigest":"sha256:e","hasUpdate":true,"error":"rate limited"},
          {"image":"x:1","stack":"data","localDigest":"sha256:d","remoteDigest":"sha256:e","hasUpdate":true,"ignored":true}
        ]}""",
    )

    @Test fun parsesImageStatusAndCountsOnlyTrustworthyUpdates() {
        val images = StackParser.parseImageStatus(statusReply)
        assertEquals(4, images.size)
        assertEquals(listOf(true, false, false, false), images.map { it.updateAvailable })
        assertEquals("rate limited", images[2].error)
        assertNull(images[0].error)
        assertTrue(images[3].ignored)
        assertEquals(2026, images[0].lastChecked!!.atZone(java.time.ZoneOffset.UTC).year)
    }

    @Test fun serverUpdatesGroupsByStack() {
        val s = com.feerlessleadr.dockge.api.ServerUpdates(true, StackParser.parseImageStatus(statusReply))
        assertEquals(listOf("nginx:latest"), s.availableFor("web").map { it.image })
        assertTrue(s.availableFor("data").isEmpty())
        assertEquals(1, s.availableCount)
    }

    @Test fun errorReplyIsAnApiException() {
        val r = runCatching { StackParser.parseImageStatus(j("""{"ok":false,"msg":"nope"}""")) }
        assertEquals("nope", r.exceptionOrNull()!!.message)
    }

    @Test fun parsesWatcherEnabled() {
        assertTrue(StackParser.parseWatcherEnabled(j("""{"ok":true,"data":{"enabled":true,"autoUpdateConfig":{}}}""")))
        assertFalse(StackParser.parseWatcherEnabled(j("""{"ok":true,"data":{"enabled":false}}""")))
    }

    @Test fun registryLinksFollowTheForksRules() {
        assertEquals("https://hub.docker.com/_/nginx", ImageLinks.registryUrl("nginx:latest"))
        assertEquals("https://hub.docker.com/_/nginx", ImageLinks.registryUrl("docker.io/library/nginx"))
        assertEquals("https://hub.docker.com/r/user/app", ImageLinks.registryUrl("user/app:1.2"))
        assertEquals("https://github.com/owner/repo", ImageLinks.registryUrl("ghcr.io/owner/repo/sub:tag"))
        assertEquals("https://quay.io/repository/org/img", ImageLinks.registryUrl("quay.io/org/img:1"))
        assertEquals("https://hub.docker.com/r/linuxserver/sonarr", ImageLinks.registryUrl("lscr.io/linuxserver/sonarr:latest"))
        assertEquals("https://gitlab.com/g/p", ImageLinks.registryUrl("registry.gitlab.com/g/p:x"))
        assertEquals("https://hub.docker.com/_/nginx", ImageLinks.registryUrl("nginx@sha256:abc"))
        assertNull("private registry has no reliable link", ImageLinks.registryUrl("registry.example.com/team/app:1"))
        assertNull(ImageLinks.registryUrl(""))
    }

    @Test fun shortDigestIsTwelveCharacters() {
        assertEquals("aaaaaaaaaaaa", ImageLinks.shortDigest("sha256:aaaaaaaaaaaaaaaaaaaa"))
        assertEquals("unknown", ImageLinks.shortDigest(""))
    }

    @Test fun storeAsksEachServerAndKeepsTheLastResultWhenOneFails() = runTest {
        val socket = FakeSocket()
        var failH2 = false
        socket.handler = { _, args ->
            val endpoint = args[0].jsonPrimitive.content
            when (args[1].jsonPrimitive.content) {
                "watcherImageStatusGet" -> if (endpoint == "h2" && failH2) throw CallTimeoutException("x") else statusReply
                "watcherImageAutoUpdateGet" -> j("""{"ok":true,"data":{"enabled":${endpoint != "h2"}}}""")
                else -> error("unexpected")
            }
        }
        val store = UpdateStatusStore(socket)
        store.refresh(listOf("", "h2"))
        assertEquals(setOf("", "h2"), store.byServer.value.keys)
        assertTrue(store.byServer.value.getValue("").watcherEnabled)
        assertFalse(store.byServer.value.getValue("h2").watcherEnabled)
        failH2 = true
        store.refresh(listOf("", "h2"))
        assertEquals("h2 keeps its last result", 4, store.byServer.value.getValue("h2").images.size)
    }

    @Test fun storeAssumesTheWatcherIsOnWhenThatCallFails() = runTest {
        val socket = FakeSocket()
        socket.handler = { _, args ->
            if (args[1].jsonPrimitive.content == "watcherImageStatusGet") statusReply else j("""{"ok":false,"msg":"x"}""")
        }
        val store = UpdateStatusStore(socket)
        store.refresh(listOf(""))
        assertTrue(store.byServer.value.getValue("").watcherEnabled)
    }

    @Test fun storeAsksOnlyReadOnlyEvents() = runTest {
        val socket = FakeSocket()
        socket.handler = { _, args ->
            if (args[1].jsonPrimitive.content == "watcherImageStatusGet") statusReply else j("""{"ok":true,"data":{"enabled":true}}""")
        }
        UpdateStatusStore(socket).refresh(listOf("h2"))
        assertEquals(setOf("watcherImageStatusGet", "watcherImageAutoUpdateGet"), socket.calls.map { it.second[1].jsonPrimitive.content }.toSet())
        assertTrue(socket.calls.all { it.second[0].jsonPrimitive.content == "h2" })
    }

    @Test fun managerAsksForUpdatesAfterTheStackListAndAgainEveryFiveMinutes() = runTest {
        val vault = MemoryVault()
        val repo = InstanceRepository(MemoryInstanceStore(), vault)
        lateinit var socket: FakeSocket
        val manager = SessionManager(repo, vault, backgroundScope) { _ ->
            FakeSocket().also {
                socket = it
                it.handler = { _, args ->
                    when (args.getOrNull(1)?.jsonPrimitive?.content) {
                        "watcherImageStatusGet" -> statusReply
                        "watcherImageAutoUpdateGet" -> j("""{"ok":true,"data":{"enabled":true}}""")
                        else -> j("""{"ok":true}""")
                    }
                }
            }
        }
        val a = repo.add("A", "https://a.example.com", InstanceColors.palette[0]).getOrThrow()
        vault.put(a.id, "T") // a saved token: the session logs in by itself and becomes Connected
        manager.start(); runCurrent()
        assertEquals(ConnectionState.Connected, manager.states.value[a.id])
        assertNull("nothing is asked before the stack list is known", manager.updates.value[a.id])
        socket.push("agent", JsonPrimitive("stackList"), j("""{"ok":true,"endpoint":"","stackList":{"web":{"name":"web","status":3}}}"""))
        runCurrent()
        assertEquals(1, manager.updates.value.getValue(a.id).getValue("").availableCount)
        // The login call has one argument; only "agent" calls carry the endpoint and event name.
        fun statusCalls() = socket.calls.count { it.first == "agent" && it.second[1].jsonPrimitive.content == "watcherImageStatusGet" }
        val firstRound = statusCalls()
        assertEquals(1, firstRound)
        advanceTimeBy(5 * 60_000L + 1); runCurrent()
        assertEquals(firstRound + 1, statusCalls())
        // Coming back to the app asks again at once, not only after the next 5 minute round.
        manager.recover(); runCurrent()
        assertEquals(firstRound + 2, statusCalls())
        repo.delete(a.id); runCurrent()
        assertNull(manager.updates.value[a.id])
    }
}
