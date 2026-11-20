package com.feerlessleadr.dockge.notify

import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.data.MemoryVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class GotifyAlertsTest {
    private fun frame(id: Int, title: String = "t$id", message: String = "m$id") =
        """{"id":$id,"appid":3,"title":"$title","message":"$message","priority":5,"date":"2026-11-20T10:00:00.5045044+01:00"}"""

    // --- Gotify parsing ---

    @Test fun parsesAGotifyFrame() {
        val m = GotifyParser.parseFrame(frame(42, "[nas] Crash loop - web", "restarted"))!!
        assertEquals("g42", m.id)
        assertEquals("[nas] Crash loop - web", m.title)
        assertEquals("restarted", m.message)
        assertEquals(5, m.priority)
        assertEquals("3", m.topic)
        assertEquals(1_795_165_200L, m.time)
    }

    @Test fun badGotifyFramesAreIgnored() {
        assertNull(GotifyParser.parseFrame("nope"))
        assertNull(GotifyParser.parseFrame("""{"message":"no id"}"""))
        assertEquals(0L, GotifyParser.parseFrame("""{"id":1,"date":"not a date"}""")!!.time)
    }

    @Test fun cursorOnlyUnderstandsGotifyIds() {
        assertEquals(42L, GotifyParser.cursorId("g42"))
        assertNull("the first-run timestamp is not an id", GotifyParser.cursorId("1700000123"))
        assertNull(GotifyParser.cursorId("gabc"))
    }

    // --- Gotify client against a local server (WebSocket + REST catch-up) ---

    private fun gotifyServer(keys: MutableList<String?>, stream: (WebSocket) -> Unit = {}, streamCode: Int = 101) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                keys += request.getHeader("X-Gotify-Key")
                return when (request.requestUrl?.encodedPath) {
                    "/stream" ->
                        if (streamCode != 101) MockResponse().setResponseCode(streamCode)
                        else MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) = stream(webSocket)
                        })
                    "/message" -> MockResponse().setBody("""{"messages":[${frame(7)},${frame(6)},${frame(5)},${frame(4)}],"paging":{}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    @Test fun gotifyStreamSendsTheClientTokenAndCatchesUpAfterTheCursor() = runBlocking {
        val keys = CopyOnWriteArrayList<String?>()
        gotifyServer(keys, stream = { it.send(frame(8)) }).use { server ->
            val events = withTimeout(10_000) {
                GotifyClient().stream(server.url("/").toString().trimEnd('/'), "", "ctok", "g5").take(4).toList()
            }
            assertEquals(AlertEvent.Open, events.first())
            val ids = events.drop(1).map { (it as AlertEvent.Message).message.id }
            assertEquals(setOf("g6", "g7", "g8"), ids.toSet())
            assertEquals("catch-up is delivered oldest first", listOf("g6", "g7"), ids.filter { it != "g8" })
            assertTrue("both the socket and the catch-up call carry the token", keys.isNotEmpty() && keys.all { it == "ctok" })
        }
    }

    @Test fun gotifyFirstRunDoesNotReplayHistory() = runBlocking {
        val keys = CopyOnWriteArrayList<String?>()
        gotifyServer(keys, stream = { it.send(frame(9)) }).use { server ->
            val events = withTimeout(10_000) {
                GotifyClient().stream(server.url("/").toString().trimEnd('/'), "", "ctok", "1795000000").take(2).toList()
            }
            assertEquals(listOf("g9"), events.drop(1).map { (it as AlertEvent.Message).message.id })
            assertEquals(1, server.requestCount) // only the socket, no /message call
        }
    }

    @Test fun gotifyStreamReportsARefusedToken() = runBlocking {
        gotifyServer(CopyOnWriteArrayList(), streamCode = 401).use { server ->
            val r = runCatching { withTimeout(10_000) { GotifyClient().stream(server.url("/").toString().trimEnd('/'), "", "bad", "g1").toList() } }
            assertEquals(401, (r.exceptionOrNull() as SourceHttpException).code)
        }
    }

    // --- the listener with a Gotify-like cursor ---

    @Test fun theCursorNeverMovesBackWhenMessagesArriveOutOfOrder() = runTest {
        val store = object : SourceStore {
            var c: String? = null
            override fun load() = SourceSettings(true, "https://g.example.com", "")
            override fun save(settings: SourceSettings) {}
            override fun cursor() = c
            override fun saveCursor(id: String) { c = id }
        }
        val source = object : AlertSource {
            override fun stream(serverUrl: String, topic: String, token: String?, since: String) = flow {
                emit(AlertEvent.Open)
                emit(AlertEvent.Message(AlertMessage("g8", 0, "", "a", "a", 0, emptyList())))
                emit(AlertEvent.Message(AlertMessage("g6", 0, "", "b", "b", 0, emptyList())))
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val got = mutableListOf<String>()
        AlertListener(
            store, MemoryVault(), source, "k", backgroundScope, needsTopic = false,
            isNewer = { old, new -> new.removePrefix("g").toLong() > old.removePrefix("g").toLong() },
            clock = { 0L },
        ) { got += it.id }.apply()
        runCurrent()
        assertEquals(listOf("g8", "g6"), got)
        assertEquals("g8", store.c)
    }

    @Test fun aSourceWithoutTopicStartsWithOnlyAServer() = runTest {
        var started = 0
        val source = object : AlertSource {
            override fun stream(serverUrl: String, topic: String, token: String?, since: String) = flow<AlertEvent> { started++; kotlinx.coroutines.awaitCancellation() }
        }
        val store = object : SourceStore {
            override fun load() = SourceSettings(true, "https://g.example.com", "")
            override fun save(settings: SourceSettings) {}
            override fun cursor(): String? = null
            override fun saveCursor(id: String) {}
        }
        AlertListener(store, MemoryVault(), source, "k", backgroundScope, needsTopic = false, clock = { 0L }) { }.apply()
        runCurrent()
        assertEquals(1, started)
        AlertListener(store, MemoryVault(), source, "k", backgroundScope, needsTopic = true, clock = { 0L }) { }.apply()
        runCurrent()
        assertEquals("ntfy needs a topic", 1, started)
    }

    // --- one event arriving through several sources ---

    @Test fun theSameEventFromTwoSourcesIsShownOnce() {
        var now = 0L
        val d = AlertDeduper(windowMs = 120_000, clock = { now })
        assertTrue(d.firstSighting("[nas] 🐳 1 update(s) available", "Stack : **web**"))
        now += 5_000
        assertFalse(d.firstSighting("[nas] 🐳 1 update(s) available", "Stack :  **web**\n"))
        assertTrue("a different text is another alert", d.firstSighting("[nas] 🐳 1 update(s) available", "Stack : **db**"))
        now += 130_000
        assertTrue("after the window the same text is a new alert", d.firstSighting("[nas] 🐳 1 update(s) available", "Stack : **web**"))
    }

    // --- every notification the fork sends ---

    @Test fun everyForkTitleHasItsKind() {
        val cases = mapOf(
            "[nas] Crash loop - web" to AlertKind.CrashLoop,
            "[nas] Healthcheck unhealthy - db" to AlertKind.Unhealthy,
            "[nas] 🐳 3 update(s) available" to AlertKind.ImageUpdate,
            "[nas] ✅ 2 image(s) auto-updated" to AlertKind.ImageUpdate,
            "[nas] 🐳 3 update(s) — 1 auto, 2 manual" to AlertKind.ImageUpdate,
            "[nas] 🐳 2 mise(s) à jour disponible(s)" to AlertKind.ImageUpdate,
            "[nas] 🐳 2 actualización(es) disponible(s)" to AlertKind.ImageUpdate,
            "[nas] 🐳 2 个更新可用" to AlertKind.ImageUpdate,
            "🔔 Dockge-Enhanced update available" to AlertKind.DockgeUpdate,
            "✅ Dockge-Enhanced updated" to AlertKind.DockgeUpdate,
            "[nas] ✅ Dockge backup successful" to AlertKind.Backup,
            "[nas] ❌ Échec du backup Dockge" to AlertKind.Backup,
            "[nas] 🚨 Security alert — nginx:latest" to AlertKind.Security,
            "[nas] 🚨 Security report — Vulnerabilities detected" to AlertKind.Security,
            "[nas] ✅ Automatic cleanup completed" to AlertKind.Cleanup,
            "Dockge-Enhanced — ❌ Échec du nettoyage automatique" to AlertKind.Cleanup,
            "⚠️ Dockge Enhanced — federation circuit breaker opened" to AlertKind.Federation,
            "🚨 Dockge Enhanced — SQLite contention detected" to AlertKind.Federation,
            "Something unknown" to AlertKind.Other,
        )
        cases.forEach { (title, kind) -> assertEquals(title, kind, AlertClassifier.kind(title, "")) }
    }

    @Test fun lockScreenTitlesAreDistinctPerKindAndNameNothing() {
        assertEquals(AlertKind.entries.size, AlertKind.entries.map { it.publicTitle }.toSet().size)
    }

    // --- tapping an update alert ---

    private val a = Instance("id-a", "A", 0, "https://a.example.com")
    private val stacks = mapOf("id-a" to listOf("" to "web", "h2:5001" to "media", "" to "db"))
    private fun update(body: String) = AlertMessage("1", 0, "t", "[nas] 🐳 1 update(s) available", body, 3, emptyList())
    private fun route(m: AlertMessage, kind: AlertKind = AlertKind.ImageUpdate) =
        AlertRouting.resolve(m, kind, listOf(a), { stacks[it].orEmpty() })

    @Test fun anUpdateForOneStackOpensThatStackOnItsServer() {
        val body = "3 image(s) checked · now\n[Open Dockge](https://a.example.com) to review pending updates.\n\n**🔄 `nginx:latest`**\nStack : **media**\nRemote : `sha256:abc…`"
        assertEquals(AlertTarget("id-a", "h2:5001", "media"), route(update(body)))
    }

    @Test fun anUpdateForSeveralStacksOpensTheInstanceOnly() {
        val body = "**🔄 `a`**\nStack : **web**\n\n**🔄 `b`**\nStack : **db**\n"
        assertEquals(AlertTarget("id-a"), route(update(body)))
    }

    @Test fun theStackLabelIsFoundInOtherLanguagesToo() {
        assertEquals("web", route(update("**🔄 `x`**\n堆栈 : **web**\n"))!!.stackName)
    }

    @Test fun anUpdateStackNotKnownToTheAppStillOpensItWithoutAServer() {
        assertEquals(AlertTarget("id-a", "", "newstack"), route(update("**🔄 `x`**\nStack : **newstack**\n")))
    }

    @Test fun backupSecurityCleanupFederationAndSelfUpdateOpenTheInstanceOnly() {
        listOf(AlertKind.Backup, AlertKind.Security, AlertKind.Cleanup, AlertKind.Federation, AlertKind.DockgeUpdate).forEach {
            assertEquals(AlertTarget("id-a"), route(update("web db media mentioned here"), it))
        }
    }
}
