package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.ImageUpdate
import com.feerlessleadr.dockge.api.ServerUpdates
import com.feerlessleadr.dockge.api.Stack
import com.feerlessleadr.dockge.api.StackStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAllTest {
    private fun stack(endpoint: String, name: String, managed: Boolean = true) = Stack(
        endpoint = endpoint, name = name, displayName = "", status = StackStatus.Running, isManaged = managed,
        isExternal = false, composeFileName = "compose.yaml", lastUpdated = null,
    )

    private fun image(stack: String, image: String, update: Boolean = true) = ImageUpdate(
        stack = stack, image = image, localDigest = "sha256:a", remoteDigest = "sha256:b",
        hasUpdate = update, lastChecked = null, ignored = false, error = null,
    )

    private fun servers(vararg list: Pair<String, List<Stack>>, status: String = "online") = list.map { (endpoint, stacks) ->
        ServerStacks(endpoint, endpoint.ifEmpty { "self" }, status, stacks)
    }

    @Test fun planKeepsManagedStacksWithUpdatesAndLeavesDockgeOut() {
        val list = servers("" to listOf(stack("", "web"), stack("", "dockge"), stack("", "idle"), stack("", "loose", managed = false)))
        val updates = mapOf(
            "" to ServerUpdates(true, listOf(
                image("web", "nginx:latest"),
                image("dockge", "ghcr.io/aerya/dockge-enhanced:latest"),
                image("loose", "redis:7"),
            )),
        )
        val plan = UpdateAll.plan(list, updates, selfRepo = "aerya/dockge-enhanced")
        assertEquals(listOf("web"), plan.map { it.stackName })
    }

    @Test fun dockgeIsExcludedOnLinkedServersToo() {
        val list = servers("" to emptyList(), "h2:3001" to listOf(stack("h2:3001", "dge"), stack("h2:3001", "app")))
        val updates = mapOf(
            "h2:3001" to ServerUpdates(true, listOf(
                image("dge", "ghcr.io/aerya/dockge-enhanced:1.5.4"),
                image("app", "postgres:16"),
            )),
        )
        assertEquals(listOf("app"), UpdateAll.plan(list, updates, selfRepo = null).map { it.stackName })
    }

    @Test fun offlineServersAreSkipped() {
        val list = servers("h2:3001" to listOf(stack("h2:3001", "app")), status = "offline")
        val updates = mapOf("h2:3001" to ServerUpdates(true, listOf(image("app", "postgres:16"))))
        assertTrue(UpdateAll.plan(list, updates, null).isEmpty())
    }

    @Test fun ignoredOrFailedChecksAreNotUpdates() {
        val list = servers("" to listOf(stack("", "a"), stack("", "b")))
        val updates = mapOf("" to ServerUpdates(true, listOf(
            image("a", "x:1", update = false),
            ImageUpdate("b", "y:1", "sha256:a", "sha256:b", true, null, ignored = true, error = null),
        )))
        assertTrue(UpdateAll.plan(list, updates, null).isEmpty())
    }

    @Test fun runUpdatesEachStackInOrderAndKeepsGoingAfterAFailure() = runTest {
        val targets = listOf(UpdateTarget("", "a", "A"), UpdateTarget("", "b", "B"), UpdateTarget("", "c", "C"))
        val ran = mutableListOf<String>()
        val final = UpdateAll.run(
            targets = targets,
            isConnected = { true },
            runOne = { t -> ran += t.stackName; if (t.stackName == "b") ActionOutcome.Failed("boom") else ActionOutcome.Done },
            onChange = {},
        )
        assertEquals(listOf("a", "b", "c"), ran)
        assertEquals(3, final.done)
        assertEquals(1, final.failed)
        assertTrue(!final.running)
        assertNull(final.stoppedBecause)
    }

    @Test fun runStopsWhenTheConnectionIsLost() = runTest {
        val targets = listOf(UpdateTarget("", "a", "A"), UpdateTarget("", "b", "B"))
        var connected = true
        val ran = mutableListOf<String>()
        val final = UpdateAll.run(
            targets = targets,
            isConnected = { connected },
            runOne = { t -> ran += t.stackName; connected = false; ActionOutcome.Failed("The connection was lost.") },
            onChange = {},
        )
        assertEquals(listOf("a"), ran)
        assertEquals(1, final.done)
        assertNotNull(final.stoppedBecause)
    }
}
