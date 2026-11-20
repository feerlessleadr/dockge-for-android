package com.feerlessleadr.dockge.notify

import com.feerlessleadr.dockge.api.ImageUpdate
import com.feerlessleadr.dockge.api.ServerUpdates
import com.feerlessleadr.dockge.core.Instance
import com.feerlessleadr.dockge.data.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class MemorySeen : SeenStore {
    val map = mutableMapOf<String, List<String>>()
    override fun load(instanceId: String) = map[instanceId]
    override fun save(instanceId: String, keys: List<String>) { map[instanceId] = keys }
    override fun remove(instanceId: String) { map.remove(instanceId) }
}

private class MemorySettings : AlertSettingsStore {
    var s = AlertSettings(enabled = true)
    override fun load() = s
    override fun save(settings: AlertSettings) { s = settings }
}

private val inst = Instance("i1", "Home", 0, "https://dockge.example.com")
private val stacks = listOf("" to "web", "" to "web-app", "h2:5001" to "media")

private fun overview(
    crashes: List<CrashEvent> = emptyList(),
    health: List<HealthEvent> = emptyList(),
    backup: Pair<String, Boolean>? = null,
    security: List<SecurityFinding> = emptyList(),
) = MonitoringOverview(backup?.first, backup?.second, security, crashes, health)

private fun crash(id: String, name: String = "web-app-web-1", ts: String = "2026-11-20T10:00:00.000Z") = CrashEvent(name, id, 5, 10, ts)
private fun health(id: String, name: String = "media-jellyfin-1", stack: String? = "media", ts: String = "2026-11-20T10:00:00.000Z") =
    HealthEvent(name, id, stack, "jellyfin", "unhealthy", "success", "Health check failing: timeout", ts)

private fun update(stack: String, image: String, digest: String, hasUpdate: Boolean = true, error: String? = null) =
    ImageUpdate(stack, image, "sha256:old", digest, hasUpdate, null, false, error)

@OptIn(ExperimentalCoroutinesApi::class)
class AlertTest {
    // --- parser ---

    private val overviewJson = """{"ok":true,"data":{
      "backup":{"lastTimestamp":"2026-11-20T03:00:00.000Z","ageMinutes":420,"success":false},
      "images":{"pendingCount":1,"pendingImages":[{"image":"nginx","stack":"web"}]},
      "trivy":{"criticalCount":1,"criticalImages":[{"image":"nginx:1.0","stack":"web","maxSeverity":"CRITICAL"}],"lastScanAt":null,"nextScanAt":null},
      "crashes":[{"containerName":"web-web-1","containerId":"abc","restartCount":7,"windowMinutes":5,"timestamp":"2026-11-20T10:00:00.000Z"}],
      "health":[{"containerName":"db-1","containerId":"def","stackName":"db","serviceName":"db","healthStatus":"unhealthy","action":"notify","actionStatus":"success","message":"boom","timestamp":"2026-11-20T10:01:00.000Z"}]
    }}"""

    @Test fun parsesTheMonitoringOverview() {
        val o = MonitoringParser.parseOverview(overviewJson)
        assertEquals("2026-11-20T03:00:00.000Z", o.backupTimestamp)
        assertEquals(false, o.backupSuccess)
        assertEquals(listOf(SecurityFinding("nginx:1.0", "web", "CRITICAL")), o.security)
        assertEquals(CrashEvent("web-web-1", "abc", 7, 5, "2026-11-20T10:00:00.000Z"), o.crashes.single())
        assertEquals("db", o.health.single().stackName)
        assertEquals("boom", o.health.single().message)
    }

    @Test fun missingPartsAreEmptyNotErrors() {
        val o = MonitoringParser.parseOverview("""{"ok":true,"data":{"backup":{"lastTimestamp":null,"success":null}}}""")
        assertNull(o.backupTimestamp)
        assertNull(o.backupSuccess)
        assertTrue(o.crashes.isEmpty() && o.health.isEmpty() && o.security.isEmpty())
    }

    @Test fun errorRepliesAreExceptions() {
        assertTrue(runCatching { MonitoringParser.parseOverview("""{"ok":false,"message":"nope"}""") }.exceptionOrNull()!!.message == "nope")
        assertTrue(runCatching { MonitoringParser.parseOverview("not json") }.isFailure)
        assertTrue(runCatching { MonitoringParser.parseOverview("""{"ok":true}""") }.isFailure)
    }

    // --- REST client against a local server ---

    @Test fun clientSendsTheTokenInAHeaderToTheOverviewPath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(overviewJson))
            server.start()
            val base = server.url("/").toString().trimEnd('/') + "/dockge"
            val o = MonitoringClient().overview(base, "jwt-secret")
            assertEquals(1, o.crashes.size)
            val request = server.takeRequest()
            assertEquals("/dockge/api/monitoring/overview", request.path)
            assertEquals("Bearer jwt-secret", request.getHeader("Authorization"))
            assertTrue("no token in the URL", !request.path!!.contains("token"))
        }
    }

    @Test fun clientMapsHttpErrorsAndDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://elsewhere.invalid/"))
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            val c = MonitoringClient()
            assertEquals(401, (runCatching { c.overview(base, "t") }.exceptionOrNull() as MonitoringHttpException).code)
            assertTrue(runCatching { c.overview(base, "t") }.exceptionOrNull()!!.message!!.contains("dockge-enhanced"))
            assertEquals(302, (runCatching { c.overview(base, "t") }.exceptionOrNull() as MonitoringHttpException).code)
            assertEquals(3, server.requestCount)
        }
    }

    // --- engine ---

    private class Collector {
        val alerts = mutableListOf<Alert>()
        val emit: (Alert) -> Unit = { alerts += it }
    }

    private fun engine(c: Collector, seen: SeenStore = MemorySeen(), s: AlertSettings = AlertSettings(enabled = true)) =
        AlertEngine(seen, { s }, c.emit, clock = { 1_000L })

    @Test fun theFirstLookIsSilentThenNewEventsAlert() {
        val c = Collector()
        val e = engine(c)
        e.onOverview(inst, overview(crashes = listOf(crash("c1"))), stacks)
        assertTrue("existing events are old news", c.alerts.isEmpty())
        e.onOverview(inst, overview(crashes = listOf(crash("c2", ts = "2026-11-20T11:00:00.000Z"), crash("c1"))), stacks)
        val a = c.alerts.single()
        assertEquals(AlertKind.CrashLoop, a.kind)
        assertEquals("Crash loop: web-app-web-1", a.title)
        assertEquals("Home: restarted 5 times in 10 min.", a.text)
    }

    @Test fun theSameEventNeverAlertsTwiceEvenAfterARestart() {
        val c = Collector()
        val seen = MemorySeen()
        engine(c, seen).apply {
            onOverview(inst, overview(), stacks)
            onOverview(inst, overview(crashes = listOf(crash("c1"))), stacks)
        }
        assertEquals(1, c.alerts.size)
        val after = Collector()
        engine(after, seen).onOverview(inst, overview(crashes = listOf(crash("c1"))), stacks) // new engine, same store
        assertTrue(after.alerts.isEmpty())
    }

    @Test fun aCrashOpensTheStackWhoseNamePrefixesTheContainerNameLongestFirst() {
        val c = Collector()
        val e = engine(c)
        e.onOverview(inst, overview(), stacks)
        e.onOverview(inst, overview(crashes = listOf(crash("c1", "web-app-web-1"), crash("c2", "other-1", "2026-11-20T10:05:00.000Z"))), stacks)
        assertEquals(AlertTarget("i1", "", "web-app"), c.alerts.first { it.title.contains("web-app") }.target)
        assertEquals(AlertTarget("i1"), c.alerts.first { it.title.contains("other") }.target)
    }

    @Test fun stackForNeedsASeparatorSoWebIsNotWebserver() {
        assertNull(AlertEngine.stackFor("webserver-1", stacks))
        assertEquals("" to "web", AlertEngine.stackFor("web-nginx-1", stacks))
        assertEquals("h2:5001" to "media", AlertEngine.stackFor("Media_jellyfin_1", stacks))
    }

    @Test fun unhealthyUsesTheStackNameTheServerGaveAndItsServer() {
        val c = Collector()
        val e = engine(c)
        e.onOverview(inst, overview(), stacks)
        e.onOverview(inst, overview(health = listOf(health("h1"))), stacks)
        val a = c.alerts.single()
        assertEquals(AlertKind.Unhealthy, a.kind)
        assertEquals(AlertTarget("i1", "h2:5001", "media"), a.target)
        assertTrue(a.text.contains("timeout"))
    }

    @Test fun onlyBackupFailuresAlert() {
        val c = Collector()
        val e = engine(c)
        e.onOverview(inst, overview(backup = "2026-11-19T03:00:00.000Z" to true), stacks)
        e.onOverview(inst, overview(backup = "2026-11-20T03:00:00.000Z" to true), stacks)
        assertTrue("a good backup is not news", c.alerts.isEmpty())
        e.onOverview(inst, overview(backup = "2026-11-21T03:00:00.000Z" to false), stacks)
        assertEquals(AlertKind.Backup, c.alerts.single().kind)
        e.onOverview(inst, overview(backup = "2026-11-21T03:00:00.000Z" to false), stacks)
        assertEquals("the same failure is reported once", 1, c.alerts.size)
    }

    @Test fun securityFindingsAlertOncePerImageAndSeverity() {
        val c = Collector()
        val e = engine(c)
        e.onOverview(inst, overview(), stacks)
        val f = SecurityFinding("nginx:1.0", "web", "CRITICAL")
        e.onOverview(inst, overview(security = listOf(f)), stacks)
        e.onOverview(inst, overview(security = listOf(f)), stacks)
        assertEquals(1, c.alerts.size)
        assertEquals(AlertTarget("i1", "", "web"), c.alerts.single().target)
        e.onOverview(inst, overview(security = listOf(f, f.copy(severity = "HIGH"))), stacks)
        assertEquals(2, c.alerts.size)
    }

    @Test fun switchedOffKindsStaySilentButAreStillRememberedSoTurningThemOnDoesNotFlood() {
        val c = Collector()
        var s = AlertSettings(enabled = true, containers = false)
        val e = AlertEngine(MemorySeen(), { s }, c.emit)
        e.onOverview(inst, overview(), stacks)
        e.onOverview(inst, overview(crashes = listOf(crash("c1"))), stacks)
        assertTrue(c.alerts.isEmpty())
        s = s.copy(containers = true)
        e.onOverview(inst, overview(crashes = listOf(crash("c1"))), stacks)
        assertTrue("the event happened while the kind was off", c.alerts.isEmpty())
    }

    @Test fun updatesAlertPerStackForNewImagesAndNewDigestsOnly() {
        val c = Collector()
        val e = engine(c)
        fun server(vararg u: ImageUpdate) = mapOf("" to ServerUpdates(true, u.toList()))
        e.onUpdates(inst, server(update("web", "nginx", "sha256:a")))
        assertTrue("existing updates are old news", c.alerts.isEmpty())
        e.onUpdates(inst, server(update("web", "nginx", "sha256:a"), update("db", "postgres", "sha256:p"), update("db", "redis", "sha256:r")))
        assertEquals(1, c.alerts.size)
        val a = c.alerts.single()
        assertEquals(AlertKind.ImageUpdate, a.kind)
        assertEquals("Update available: db", a.title)
        assertEquals("Home: postgres, redis", a.text)
        assertEquals(AlertTarget("i1", "", "db"), a.target)
        e.onUpdates(inst, server(update("web", "nginx", "sha256:a2")))
        assertEquals("a newer digest for the same image is a new update", 2, c.alerts.size)
        e.onUpdates(inst, server(update("web", "nginx", "sha256:a2", hasUpdate = false), update("x", "y", "sha256:z", error = "rate limited")))
        assertEquals("checks that failed or found nothing do not alert", 2, c.alerts.size)
    }

    @Test fun anUpdateOnALinkedServerOpensThatServersStack() {
        val c = Collector()
        val e = engine(c)
        e.onUpdates(inst, mapOf("h2:5001" to ServerUpdates(true, emptyList())))
        e.onUpdates(inst, mapOf("h2:5001" to ServerUpdates(true, listOf(update("media", "jellyfin", "sha256:j")))))
        assertEquals(AlertTarget("i1", "h2:5001", "media"), c.alerts.single().target)
        assertTrue(c.alerts.single().text.contains("h2:5001"))
    }

    @Test fun oldKeysAreDroppedButNeverTheBaselineMarkers() {
        val c = Collector()
        val seen = MemorySeen()
        val e = engine(c, seen)
        e.onOverview(inst, overview(), stacks)
        repeat(AlertEngine.MAX_KEYS + 50) { n ->
            e.onOverview(inst, overview(crashes = listOf(crash("c$n", ts = "t$n"))), stacks)
        }
        val keys = seen.map.getValue("i1")
        assertTrue(keys.size <= AlertEngine.MAX_KEYS + 1)
        assertTrue(keys.any { it.startsWith("baseline:crash") })
        c.alerts.clear()
        engine(c, seen).onOverview(inst, overview(crashes = listOf(crash("fresh", ts = "tz"))), stacks)
        assertEquals("a restart after many events still alerts", 1, c.alerts.size)
    }

    @Test fun forgettingAnInstanceStartsItSilentAgain() {
        val c = Collector()
        val seen = MemorySeen()
        val e = engine(c, seen)
        e.onOverview(inst, overview(), stacks)
        e.forget("i1")
        assertNull(seen.map["i1"])
        e.onOverview(inst, overview(crashes = listOf(crash("c1"))), stacks)
        assertTrue(c.alerts.isEmpty())
    }

    // --- watcher ---

    private class FakeFeed : MonitoringSource {
        val calls = mutableListOf<Pair<String, String?>>()
        var result: () -> MonitoringOverview = { overview() }
        override suspend fun overview(baseUrl: String, token: String?): MonitoringOverview {
            calls += baseUrl to token
            return result()
        }
    }

    private class Rig(scope: kotlinx.coroutines.CoroutineScope, now: () -> Long, val feed: FakeFeed = FakeFeed(), val tokens: MutableMap<String, String> = mutableMapOf("i1" to "tok")) {
        val instances = MutableStateFlow(listOf(inst))
        val states = MutableStateFlow<Map<String, ConnectionState>>(mapOf("i1" to ConnectionState.Connected))
        val updates = MutableStateFlow<Map<String, Map<String, ServerUpdates>>>(emptyMap())
        val settings = MemorySettings()
        val alerts = mutableListOf<Alert>()
        val seen = MemorySeen()
        val watcher = AlertWatcher(
            instances, states, updates, { listOf("" to "web") }, { tokens[it] }, feed,
            AlertEngine(seen, settings::load, { alerts += it }, now), settings, scope, now, pollMs = 60_000, tickMs = 5_000,
        )
    }

    @Test fun theWatcherPollsConnectedInstancesWithTheirTokenEveryMinute() = runTest {
        val r = Rig(backgroundScope, { currentTime })
        r.watcher.apply(); runCurrent()
        assertEquals(listOf("https://dockge.example.com" to "tok"), r.feed.calls)
        advanceTimeBy(30_000); runCurrent()
        assertEquals("not again before a minute", 1, r.feed.calls.size)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(2, r.feed.calls.size)
        assertTrue(r.watcher.status.value["i1"] is PollStatus.Ok)
    }

    @Test fun anInstanceThatIsNotConnectedIsNotPolledUntilItIs() = runTest {
        val r = Rig(backgroundScope, { currentTime })
        r.states.value = mapOf("i1" to ConnectionState.Connecting)
        r.watcher.apply(); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertTrue(r.feed.calls.isEmpty())
        r.states.value = mapOf("i1" to ConnectionState.Connected)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, r.feed.calls.size)
    }

    @Test fun nothingRunsWhileAlertsAreOffAndTurningThemOffStops() = runTest {
        val r = Rig(backgroundScope, { currentTime })
        r.settings.s = AlertSettings(enabled = false)
        r.watcher.apply(); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertTrue(r.feed.calls.isEmpty())
        r.settings.s = AlertSettings(enabled = true)
        r.watcher.apply(); runCurrent()
        assertEquals(1, r.feed.calls.size)
        r.settings.s = AlertSettings(enabled = false)
        r.watcher.apply(); runCurrent()
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, r.feed.calls.size)
    }

    @Test fun aFailingFeedIsReportedAndRetriedNextMinute() = runTest {
        val r = Rig(backgroundScope, { currentTime })
        r.feed.result = { throw MonitoringHttpException(401) }
        r.watcher.apply(); runCurrent()
        val failed = r.watcher.status.value["i1"] as PollStatus.Failed
        assertTrue(failed.message.contains("Sign in again"))
        r.feed.result = { overview() }
        advanceTimeBy(61_000); runCurrent()
        assertTrue(r.watcher.status.value["i1"] is PollStatus.Ok)
    }

    @Test fun withoutASavedTokenTheFeedIsNotCalled() = runTest {
        val r = Rig(backgroundScope, { currentTime }, tokens = mutableMapOf())
        r.watcher.apply(); runCurrent()
        assertTrue(r.feed.calls.isEmpty())
        assertEquals(PollStatus.Failed("Not signed in."), r.watcher.status.value["i1"])
    }

    @Test fun aNewCrashFromTheFeedBecomesAnAlertAndAnUpdateFromTheStatusToo() = runTest {
        val r = Rig(backgroundScope, { currentTime })
        r.watcher.apply(); runCurrent()
        r.feed.result = { overview(crashes = listOf(crash("c1"))) }
        advanceTimeBy(61_000); runCurrent()
        assertEquals(AlertKind.CrashLoop, r.alerts.single().kind)
        r.updates.value = mapOf("i1" to mapOf("" to ServerUpdates(true, emptyList()))); runCurrent()
        r.updates.value = mapOf("i1" to mapOf("" to ServerUpdates(true, listOf(update("web", "nginx", "sha256:n"))))); runCurrent()
        assertEquals(listOf(AlertKind.CrashLoop, AlertKind.ImageUpdate), r.alerts.map { it.kind })
    }

    @Test fun deletingAnInstanceDropsItsMemoryAndStatus() = runTest {
        val r = Rig(backgroundScope, { currentTime })
        r.watcher.apply(); runCurrent()
        assertTrue(r.seen.map.containsKey("i1"))
        r.instances.value = emptyList(); runCurrent()
        assertNull(r.seen.map["i1"])
        assertNull(r.watcher.status.value["i1"])
    }

    @Test fun anIoErrorFromTheFeedDoesNotStopTheLoop() = runTest {
        val r = Rig(backgroundScope, { currentTime })
        r.feed.result = { throw IOException("down") }
        r.watcher.apply(); runCurrent()
        advanceTimeBy(130_000); runCurrent()
        assertTrue(r.feed.calls.size >= 3)
    }

    // --- links ---

    @Test fun deepLinksRoundTrip() {
        listOf(AlertTarget("id-a"), AlertTarget("id-a", "", "web"), AlertTarget("id-a", "h2:5001", "my stack+1")).forEach {
            assertEquals(it, DeepLink.parse(DeepLink.build(it)))
        }
        assertEquals("dockge://instance/id-a/stack/web", DeepLink.build(AlertTarget("id-a", "", "web")))
        assertNull(DeepLink.parse("https://instance/id-a"))
        assertNull(DeepLink.parse("dockge://other/id-a"))
        assertNull(DeepLink.parse(null))
    }

    @Test fun lockScreenTitlesAreDistinctPerKind() {
        assertEquals(AlertKind.entries.size, AlertKind.entries.map { it.publicTitle }.toSet().size)
    }
}
