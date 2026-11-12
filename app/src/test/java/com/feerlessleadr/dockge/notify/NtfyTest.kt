package com.feerlessleadr.dockge.notify

import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.data.MemoryVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class MemoryNtfyStore(var settings: NtfySettings = NtfySettings(true, "https://ntfy.example.com", "dockge")) : NtfyStore {
    var cursor: String? = null
    override fun load() = settings
    override fun save(settings: NtfySettings) { this.settings = settings }
    override fun cursor() = cursor
    override fun saveCursor(id: String) { cursor = id }
}

private class FakeSource(val script: (call: Int, since: String) -> Flow<NtfyEvent>) : NtfySource {
    val sinces = mutableListOf<String>()
    val tokens = mutableListOf<String?>()
    override fun stream(serverUrl: String, topic: String, token: String?, since: String): Flow<NtfyEvent> {
        sinces += since
        tokens += token
        return script(sinces.size, since)
    }
}

private fun msg(id: String, title: String = "t", body: String = "b") =
    NtfyMessage(id, 1_700_000_000, "dockge", title, body, 3, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class NtfyTest {
    // --- parsing ---

    @Test fun parsesAMessageLine() {
        val m = NtfyParser.parseLine(
            """{"id":"abc","time":1700000000,"event":"message","topic":"dockge","title":"[host] Crash loop - web","message":"Container **web** restarted","priority":4,"tags":["warning","x"]}""",
        )!!
        assertEquals("abc", m.id)
        assertEquals(1_700_000_000L, m.time)
        assertEquals("[host] Crash loop - web", m.title)
        assertEquals(4, m.priority)
        assertEquals(listOf("warning", "x"), m.tags)
    }

    @Test fun ignoresKeepaliveOpenGarbageAndBlankLines() {
        assertNull(NtfyParser.parseLine("""{"id":"1","time":1,"event":"keepalive","topic":"dockge"}"""))
        assertNull(NtfyParser.parseLine("""{"id":"1","time":1,"event":"open","topic":"dockge"}"""))
        assertNull(NtfyParser.parseLine("not json"))
        assertNull(NtfyParser.parseLine("   "))
        assertNull(NtfyParser.parseLine("""{"event":"message"}"""))
    }

    @Test fun missingOptionalFieldsGetDefaults() {
        val m = NtfyParser.parseLine("""{"id":"1","event":"message","message":"hi"}""")!!
        assertEquals("", m.title)
        assertEquals(3, m.priority)
        assertTrue(m.tags.isEmpty())
    }

    // --- settings ---

    @Test fun validationCleansTheServerAndChecksTheTopic() {
        val ok = NtfyValidation.check(" ntfy.example.com/ ", " my_topic-1 ", true, allowCleartext = false).getOrThrow()
        assertEquals(NtfySettings(true, "https://ntfy.example.com", "my_topic-1"), ok)
        assertTrue(NtfyValidation.check("http://ntfy.example.com", "t", true, false).isFailure)
        assertTrue(NtfyValidation.check("http://ntfy.example.com", "t", true, true).isSuccess)
        assertTrue(NtfyValidation.check("https://ntfy.example.com", "bad topic", true, false).isFailure)
        assertTrue(NtfyValidation.check("https://ntfy.example.com", "", true, false).isFailure)
        assertTrue(NtfyValidation.check("https://ntfy.example.com", "a".repeat(65), true, false).isFailure)
    }

    // --- client against a real local HTTP server ---

    @Test fun streamEmitsOpenThenMessagesAndSendsTokenAndSince() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"id":"o","time":1,"event":"open","topic":"dockge"}""" + "\n" +
                        """{"id":"k","time":2,"event":"keepalive","topic":"dockge"}""" + "\n" +
                        """{"id":"m1","time":3,"event":"message","topic":"dockge","message":"one"}""" + "\n" +
                        "broken\n" +
                        """{"id":"m2","time":4,"event":"message","topic":"dockge","message":"two"}""" + "\n",
                ),
            )
            server.start()
            val events = NtfyClient().stream(server.url("/").toString().trimEnd('/'), "dockge", "tk_secret", "m0").toList()
            assertEquals(NtfyEvent.Open, events[0])
            assertEquals(listOf("m1", "m2"), events.drop(1).map { (it as NtfyEvent.Message).message.id })
            val request = server.takeRequest()
            assertEquals("/dockge/json?since=m0", request.path)
            assertEquals("Bearer tk_secret", request.headers["Authorization"])
        }
    }

    @Test fun streamWithoutTokenSendsNoAuthorizationHeader() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(""))
            server.start()
            NtfyClient().stream(server.url("/").toString().trimEnd('/'), "dockge", null, "1").toList()
            assertNull(server.takeRequest().headers["Authorization"])
        }
    }

    @Test fun streamFailsWithAReadableMessageOnHttpErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            val r = runCatching { NtfyClient().stream(server.url("/").toString().trimEnd('/'), "dockge", null, "1").toList() }
            val e = r.exceptionOrNull() as NtfyHttpException
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("access token"))
        }
    }

    @Test fun streamDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://elsewhere.invalid/"))
            server.start()
            val r = runCatching { NtfyClient().stream(server.url("/").toString().trimEnd('/'), "dockge", "tk", "1").toList() }
            assertEquals(302, (r.exceptionOrNull() as NtfyHttpException).code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun publishPostsToTheTopicWithTitleAndToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            val result = NtfyClient().publish(server.url("/").toString().trimEnd('/'), "dockge", "tk", "Dockge test", "hello")
            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/dockge", request.path)
            assertEquals("Dockge test", request.headers["Title"])
            assertEquals("Bearer tk", request.headers["Authorization"])
            assertEquals("hello", request.body.readUtf8())
        }
    }

    // --- listener ---

    @Test fun firstStartBeginsNowThenHandsOverMessagesAndKeepsTheCursor() = runTest {
        val store = MemoryNtfyStore()
        val got = mutableListOf<String>()
        val source = FakeSource { _, _ -> flow { emit(NtfyEvent.Open); emit(NtfyEvent.Message(msg("m1"))); kotlinx.coroutines.awaitCancellation() } }
        val listener = NtfyListener(store, MemoryVault(), source, backgroundScope, { 1_700_000_123_456L }) { got += it.id }
        listener.apply(); runCurrent()
        assertEquals(listOf("1700000123"), source.sinces)
        assertEquals(listOf("m1"), got)
        assertEquals("m1", store.cursor)
        assertTrue(listener.state.value is NtfyState.Connected)
    }

    @Test fun reconnectsWithTheCursorAndBacksOffUpToAMinute() = runTest {
        val store = MemoryNtfyStore()
        val times = mutableListOf<Long>()
        val source = FakeSource { call, _ ->
            times += currentTime
            flow {
                if (call == 1) { emit(NtfyEvent.Open); emit(NtfyEvent.Message(msg("m1"))) }
                throw IOException("boom $call")
            }
        }
        val listener = NtfyListener(store, MemoryVault(), source, backgroundScope, { 0L }) { }
        listener.apply(); runCurrent()
        assertEquals(NtfyState.Failed("boom 1"), listener.state.value)
        advanceTimeBy(10 * 60_000L); runCurrent()
        // Call 1 opened, so the first wait is 1 s; calls that never open wait 2 s, 4 s, ... capped at 60 s.
        val gaps = times.zipWithNext { x, y -> y - x }.take(8)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), gaps)
        assertEquals("0", source.sinces.first())
        assertTrue("every retry resumes from the cursor", source.sinces.drop(1).all { it == "m1" })
    }

    @Test fun aRepeatedMessageIsHandledOnce() = runTest {
        val got = mutableListOf<String>()
        val source = FakeSource { _, _ ->
            flow { emit(NtfyEvent.Open); emit(NtfyEvent.Message(msg("a"))); emit(NtfyEvent.Message(msg("a"))); emit(NtfyEvent.Message(msg("b"))); kotlinx.coroutines.awaitCancellation() }
        }
        NtfyListener(MemoryNtfyStore(), MemoryVault(), source, backgroundScope, { 0L }) { got += it.id }.apply { apply() }
        runCurrent()
        assertEquals(listOf("a", "b"), got)
    }

    @Test fun disabledOrIncompleteSettingsOpenNothing() = runTest {
        val source = FakeSource { _, _ -> flow { } }
        val store = MemoryNtfyStore(NtfySettings(false, "https://ntfy.example.com", "dockge"))
        val listener = NtfyListener(store, MemoryVault(), source, backgroundScope, { 0L }) { }
        listener.apply(); runCurrent()
        assertEquals(NtfyState.Off, listener.state.value)
        store.settings = NtfySettings(true, "", "")
        listener.apply(); runCurrent()
        assertEquals(NtfyState.Off, listener.state.value)
        assertTrue(source.sinces.isEmpty())
    }

    @Test fun savingNewSettingsRestartsTheStreamAndTurningOffStopsIt() = runTest {
        val store = MemoryNtfyStore()
        val vault = MemoryVault().also { it.put(NtfyListener.TOKEN_KEY, "tk") }
        val source = FakeSource { _, _ -> flow { emit(NtfyEvent.Open); kotlinx.coroutines.awaitCancellation() } }
        val listener = NtfyListener(store, vault, source, backgroundScope, { 0L }) { }
        listener.apply(); runCurrent()
        listener.apply(); runCurrent()
        assertEquals(2, source.sinces.size)
        assertEquals(listOf<String?>("tk", "tk"), source.tokens)
        store.settings = store.settings.copy(enabled = false)
        listener.apply(); runCurrent()
        assertEquals(NtfyState.Off, listener.state.value)
        assertEquals(2, source.sinces.size)
    }

    @Test fun anAccessErrorIsShownAsTheStateMessage() = runTest {
        val source = FakeSource { _, _ -> flow { throw NtfyHttpException(403) } }
        val listener = NtfyListener(MemoryNtfyStore(), MemoryVault(), source, backgroundScope, { 0L }) { }
        listener.apply(); runCurrent()
        val state = listener.state.value as NtfyState.Failed
        assertTrue(state.message.contains("access token"))
    }

    // --- alerts ---

    @Test fun classifiesTheForksTitles() {
        assertEquals(AlertKind.CrashLoop, AlertClassifier.kind("[nas] Crash loop - web", ""))
        assertEquals(AlertKind.Unhealthy, AlertClassifier.kind("[nas] Healthcheck unhealthy - db", ""))
        assertEquals(AlertKind.ImageUpdate, AlertClassifier.kind("[nas] 🐳 3 update(s) available", ""))
        assertEquals(AlertKind.Other, AlertClassifier.kind("✅ Dockge-Enhanced updated", ""))
        assertEquals(AlertKind.Other, AlertClassifier.kind("Something else", ""))
    }

    @Test fun lockScreenTitlesNeverNameAStackOrContainer() {
        AlertKind.entries.forEach { assertFalse(it.publicTitle.contains("web")) }
    }

    private val a = Instance("id-a", "A", 0, "https://a.example.com")
    private val b = Instance("id-b", "B", 0, "https://b.example.com/dockge")
    private val stacks = mapOf(
        "id-a" to listOf("" to "web", "" to "web-app", "h2:5001" to "db"),
        "id-b" to listOf("" to "media"),
    )
    private fun resolve(m: NtfyMessage, instances: List<Instance> = listOf(a, b)) =
        AlertRouting.resolve(m, instances) { stacks[it].orEmpty() }

    @Test fun routesToTheInstanceWhoseAddressIsInTheMessage() {
        val t = resolve(msg("1", "Updates", "See https://b.example.com/dockge for details"))!!
        assertEquals("id-b", t.instanceId)
    }

    @Test fun routesByHostWhenThePublicUrlDiffersInSchemeOrPath() {
        assertEquals("id-b", resolve(msg("1", "x", "open http://B.example.com/other"))!!.instanceId)
    }

    @Test fun aSingleSavedInstanceIsTheFallbackAndSeveralGiveNoTarget() {
        assertEquals("id-a", resolve(msg("1", "x", "no address"), listOf(a))!!.instanceId)
        assertNull(resolve(msg("1", "x", "no address")))
    }

    @Test fun picksTheStackWhenExactlyOneKnownNameAppearsAsAWholeWord() {
        val t = resolve(msg("1", "[nas] Healthcheck unhealthy - db-1", "https://a.example.com stack db is unhealthy"))!!
        assertEquals(AlertTarget("id-a", "h2:5001", "db"), t)
    }

    @Test fun aNameInsideALongerNameDoesNotCount() {
        val t = resolve(msg("1", "x", "https://a.example.com web-app restarted"))!!
        assertEquals("web-app", t.stackName)
        assertNull(resolve(msg("1", "x", "https://a.example.com webserver restarted"))!!.stackName)
    }

    @Test fun severalStacksOrNoneGiveTheInstanceOnly() {
        assertNull(resolve(msg("1", "x", "https://a.example.com web and db"))!!.stackName)
        assertNull(resolve(msg("1", "x", "https://a.example.com nothing known"))!!.stackName)
    }

    @Test fun deepLinksRoundTrip() {
        val cases = listOf(
            AlertTarget("id-a"),
            AlertTarget("id-a", "", "web"),
            AlertTarget("id-a", "h2:5001", "my stack+1"),
        )
        cases.forEach { assertEquals(it, DeepLink.parse(DeepLink.build(it))) }
        assertEquals("dockge://instance/id-a/stack/web", DeepLink.build(AlertTarget("id-a", "", "web")))
    }

    @Test fun parseRejectsOtherLinks() {
        assertNull(DeepLink.parse(null))
        assertNull(DeepLink.parse("https://instance/id-a"))
        assertNull(DeepLink.parse("dockge://other/id-a"))
        assertNull(DeepLink.parse("dockge://instance/"))
    }
}
