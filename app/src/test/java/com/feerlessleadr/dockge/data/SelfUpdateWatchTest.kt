package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.ApiException
import com.feerlessleadr.dockge.api.SelfUpdateApi
import com.feerlessleadr.dockge.api.SelfUpdateOperation
import com.feerlessleadr.dockge.api.SelfUpdateStatus
import com.feerlessleadr.dockge.core.BaseUrl
import com.feerlessleadr.dockge.core.InstanceColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelfUpdateWatchTest {
    private val blue = InstanceColors.palette[0]
    private val digest = "sha256:" + "d".repeat(64)
    private val target = "ghcr.io/aerya/dockge-enhanced@$digest"

    /** Scripted server: the status replies in order (the last one repeats), and the start call. */
    private class FakeSelfApi : SelfUpdateApi {
        val statuses = ArrayDeque<() -> SelfUpdateStatus>()
        var lastStatus: () -> SelfUpdateStatus = { throw ApiException("unreachable") }
        var startGate: CompletableDeferred<Unit>? = null
        var startError: Exception? = null
        val starts = mutableListOf<String>()

        override suspend fun status(baseUrl: String, token: String): SelfUpdateStatus {
            val next = if (statuses.isNotEmpty()) statuses.removeFirst() else lastStatus
            return next()
        }

        override suspend fun start(baseUrl: String, token: String, targetImage: String): SelfUpdateOperation {
            starts += targetImage
            startGate?.await()
            startError?.let { throw it }
            return op("backing-up")
        }
    }

    private class Rig(scope: TestScope, val api: FakeSelfApi) {
        val vault = MemoryVault()
        val repo = InstanceRepository(MemoryInstanceStore(), vault)
        val manager = SessionManager(
            repo, vault, scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
            socketFactory = {
                FakeSocket().also { it.handler = { _, _ -> Json.parseToJsonElement("""{"ok":true}""") } }
            },
            selfClient = api,
        )
    }

    private companion object {
        fun op(state: String, stage: String? = null) = SelfUpdateOperation(
            id = "1", state = state, message = "msg $state", targetImage = "", stage = stage,
            stageHistory = emptyList(), rollbackAttempted = false,
        )

        fun status(updateAvailable: Boolean, operation: SelfUpdateOperation?) = SelfUpdateStatus(
            updateAvailable = updateAvailable,
            localDigest = "sha256:" + "a".repeat(64),
            remoteDigest = "sha256:" + "d".repeat(64),
            repo = "aerya/dockge-enhanced",
            containerName = "dockge-enhanced",
            checkedAt = null,
            error = null,
            operation = operation,
        )
    }

    @Test fun offersTheDigestAndWatchesUntilTheServerReportsTheEnd() = runTest {
        val api = FakeSelfApi()
        val gate = CompletableDeferred<Unit>()
        api.startGate = gate
        api.statuses += { status(true, null) }
        api.statuses += { status(true, op("backing-up", "backup")) }
        api.statuses += { status(true, op("waiting-health", "health-check")) }
        api.lastStatus = { status(false, op("succeeded", "health-check")) }
        val r = Rig(this, api)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(a.id, "T")
        r.manager.start(); runCurrent()

        r.manager.refreshSelfUpdate(a.id)
        r.manager.startSelfUpdate(a.id)
        runCurrent()
        assertEquals(listOf(target), api.starts)
        assertTrue(r.manager.selfUpdate.value[a.id]!!.watching)

        advanceTimeBy(20_000); runCurrent()
        gate.complete(Unit)
        advanceTimeBy(20_000); runCurrent()

        val state = r.manager.selfUpdate.value[a.id]!!
        assertFalse(state.watching)
        assertEquals("succeeded", state.status?.operation?.state)
        assertNull(state.error)
    }

    @Test fun refusedStartWithNothingRunningEndsTheWatchWithTheServerMessage() = runTest {
        val api = FakeSelfApi()
        api.startError = ApiException("A self-update is already running")
        api.lastStatus = { status(true, null) }
        val r = Rig(this, api)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(a.id, "T")
        r.manager.start(); runCurrent()

        r.manager.refreshSelfUpdate(a.id)
        r.manager.startSelfUpdate(a.id)
        advanceTimeBy(10_000); runCurrent()

        val state = r.manager.selfUpdate.value[a.id]!!
        assertFalse(state.watching)
        assertEquals("A self-update is already running", state.error)
    }

    @Test fun refusesWithoutAnAvailableUpdate() = runTest {
        val api = FakeSelfApi()
        api.lastStatus = { status(false, null) }
        val r = Rig(this, api)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(a.id, "T")
        r.manager.start(); runCurrent()
        r.manager.refreshSelfUpdate(a.id)

        r.manager.startSelfUpdate(a.id)
        runCurrent()
        assertTrue(api.starts.isEmpty())
        assertEquals("No Dockge update is available.", r.manager.selfUpdate.value[a.id]!!.error)
    }

    @Test fun withoutATokenTheStatusAsksForSignIn() = runTest {
        val api = FakeSelfApi()
        val r = Rig(this, api)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.manager.start(); runCurrent()
        r.manager.refreshSelfUpdate(a.id)

        val state = r.manager.selfUpdate.value[a.id]!!
        assertFalse(state.reachable)
        assertEquals("Sign in to check Dockge's update.", state.error)
    }

    @Test fun anUnreachableServerKeepsTheLastStatus() = runTest {
        val api = FakeSelfApi()
        api.statuses += { status(true, op("backing-up", "backup")) }
        api.lastStatus = { throw ApiException("connection refused") }
        val r = Rig(this, api)
        val a = r.repo.add("A", "https://a.example.com", blue).getOrThrow()
        r.vault.put(a.id, "T")
        r.manager.start(); runCurrent()
        r.manager.refreshSelfUpdate(a.id)
        r.manager.refreshSelfUpdate(a.id)

        val state = r.manager.selfUpdate.value[a.id]!!
        assertFalse(state.reachable)
        assertEquals("backing-up", state.status?.operation?.state)
    }
}
