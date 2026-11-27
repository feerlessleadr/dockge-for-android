package com.feerlessleadr.dockge.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class SelfUpdateTest {
    private val digest = "sha256:" + "b".repeat(64)
    private val repo = "aerya/dockge-enhanced"

    private fun status(updateAvailable: Boolean = true, remote: String = digest, op: SelfUpdateOperation? = null) = SelfUpdateStatus(
        updateAvailable = updateAvailable, localDigest = "sha256:" + "a".repeat(64), remoteDigest = remote,
        repo = repo, containerName = "dockge-enhanced", checkedAt = null, error = null, operation = op,
    )

    private fun op(state: String) = SelfUpdateOperation(
        id = "1", state = state, message = "", targetImage = "", stage = null, stageHistory = emptyList(), rollbackAttempted = false,
    )

    @Test fun parsesStatusAndLastOperation() {
        val body = """
            {"ok":true,"updateAvailable":true,"localDigest":"sha256:${"a".repeat(64)}","remoteDigest":"$digest",
             "repo":"aerya/dockge-enhanced","containerName":"dockge-enhanced","checkedAt":"2026-11-27T10:00:00.000Z",
             "error":null,
             "operation":{"id":"abc","state":"rolled-back","message":"Update failed; previous restored","targetImage":"ghcr.io/aerya/dockge-enhanced@$digest",
               "stage":"rollback","stageHistory":[
                 {"stage":"backup","skipped":false,"startedAt":"2026-11-27T10:00:00Z","finishedAt":"2026-11-27T10:01:00Z"},
                 {"stage":"prune-backup","skipped":true,"startedAt":"2026-11-27T10:01:00Z","finishedAt":"2026-11-27T10:01:00Z"}],
               "rollbackAttempted":true},
             "progress":null,"selfUpdateSettings":{"mode":"manual"}}
        """.trimIndent()
        val parsed = SelfUpdateParser.parseStatus(body)
        assertTrue(parsed.updateAvailable)
        assertEquals(repo, parsed.repo)
        assertEquals(digest, parsed.remoteDigest)
        assertEquals(Instant.parse("2026-11-27T10:00:00Z"), parsed.checkedAt)
        assertNull(parsed.error)
        val operation = parsed.operation!!
        assertEquals("rolled-back", operation.state)
        assertEquals("rollback", operation.stage)
        assertTrue(operation.rollbackAttempted)
        assertEquals(2, operation.stageHistory.size)
        assertTrue(operation.stageHistory[1].skipped)
    }

    @Test fun statusWithoutOperationIsFine() {
        val parsed = SelfUpdateParser.parseStatus("""{"ok":true,"updateAvailable":false,"localDigest":"","remoteDigest":"","repo":"aerya/dockge-enhanced","containerName":"dockge-enhanced","checkedAt":null,"error":"registry unreachable"}""")
        assertFalse(parsed.updateAvailable)
        assertNull(parsed.operation)
        assertEquals("registry unreachable", parsed.error)
    }

    @Test fun refusalInsideAnOkFalseReplyBecomesTheServerMessage() {
        try {
            SelfUpdateParser.parseStatus("""{"ok":false,"message":"nope"}""")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("nope", e.message)
        }
    }

    @Test fun startReplyCarriesTheOperationInData() {
        val operation = SelfUpdateParser.parseOperation("""{"ok":true,"data":{"id":"x1","state":"backing-up","message":"Preparing","targetImage":"t","stage":"preparing","stageHistory":[],"rollbackAttempted":false}}""")
        assertEquals("x1", operation.id)
        assertEquals("backing-up", operation.state)
    }

    @Test fun targetImageIsTheDigestUnderTheCheckedRepository() {
        assertEquals("ghcr.io/$repo@$digest", SelfUpdatePolicy.targetImage(status()))
        assertNull(SelfUpdatePolicy.targetImage(status(updateAvailable = false)))
        assertNull(SelfUpdatePolicy.targetImage(status(remote = "sha256:short")))
        assertNull(SelfUpdatePolicy.targetImage(status(remote = "")))
    }

    @Test fun recognisesTheDockgeImageInAnyTagOrDigestForm() {
        assertTrue(SelfUpdatePolicy.isDockgeImage("ghcr.io/aerya/dockge-enhanced:latest", repo))
        assertTrue(SelfUpdatePolicy.isDockgeImage("ghcr.io/aerya/dockge-enhanced", repo))
        assertTrue(SelfUpdatePolicy.isDockgeImage("ghcr.io/aerya/dockge-enhanced@sha256:${"c".repeat(64)}", repo))
        assertFalse(SelfUpdatePolicy.isDockgeImage("ghcr.io/aerya/other:1", repo))
        assertFalse(SelfUpdatePolicy.isDockgeImage("nginx:latest", repo))
        assertFalse(SelfUpdatePolicy.isDockgeImage("ghcr.io/aerya/dockge-enhanced:latest", ""))
    }

    @Test fun activeAndFinishedStatesAreDisjointAndKnown() {
        assertTrue(SelfUpdatePolicy.isActive(op("waiting-health")))
        assertFalse(SelfUpdatePolicy.isActive(op("scheduled")))
        assertTrue(SelfUpdatePolicy.isFinished(op("rollback-failed")))
        assertFalse(SelfUpdatePolicy.isFinished(op("updating")))
        assertFalse(SelfUpdatePolicy.isFinished(null))
    }
}
