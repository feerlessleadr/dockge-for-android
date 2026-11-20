package com.feerlessleadr.dockge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeProgressTest {
    // Compose redraws its block in place; the app strips the cursor moves, so each redraw is a newer copy of the block.
    private val pullMid = """
[+] Pulling 0/2
 ⠿ web Pulling
 ⠿ db Pulling
[+] Pulling 1/2
 ⠿ web Pulling
   a1b2c3d4e5f6 Downloading [==>                 ]  1.2MB/30MB
 ✔ db Pulled
"""
    private val recreateMid = pullMid + """
[+] Pulling 2/2
 ✔ web Pulled
 ✔ db Pulled
[+] Running 0/2
 ⠿ Container proj-web-1  Recreate
 ⠿ Container proj-db-1  Recreate
[+] Running 1/2
 ✔ Container proj-web-1  Started
 ⠿ Container proj-db-1  Recreate
"""
    private val allDone = recreateMid + """
[+] Running 2/2
 ✔ Container proj-web-1  Started
 ✔ Container proj-db-1  Started
"""

    @Test fun midPullFillsTheFirstHalfOfAnUpdate() {
        val v = ComposeProgress.parse(pullMid, twoPhase = true)
        assertEquals(0.25f, v.fraction!!, 0.001f)
        assertEquals("Pulling images", v.phase)
        assertEquals(listOf("web" to StepState.Working, "db" to StepState.Done), v.items.map { it.name to it.state })
    }

    @Test fun recreatingFillsTheSecondHalf() {
        val v = ComposeProgress.parse(recreateMid, twoPhase = true)
        assertEquals(0.75f, v.fraction!!, 0.001f)
        assertEquals("Recreating containers", v.phase)
        assertEquals(
            listOf("web" to StepState.Done, "db" to StepState.Done, "proj-web-1" to StepState.Done, "proj-db-1" to StepState.Working),
            v.items.map { it.name to it.state },
        )
    }

    @Test fun aRunningBarNeverReachesTheEnd() {
        val v = ComposeProgress.parse(allDone, twoPhase = true)
        assertTrue(v.fraction!! <= 0.97f && v.fraction!! > 0.9f)
    }

    @Test fun redrawnCopiesDoNotDuplicateItemsAndTheLastVerbWins() {
        val v = ComposeProgress.parse(allDone, twoPhase = true)
        assertEquals(4, v.items.size)
        assertTrue(v.items.all { it.state == StepState.Done })
        assertEquals("Started", v.items.last().verb)
    }

    @Test fun otherActionsUseTheCounterDirectly() {
        val v = ComposeProgress.parse("[+] Stopping 0/2\n ⠿ Container a  Stopping\n[+] Stopping 1/2\n ✔ Container a  Stopped\n ⠿ Container b  Stopping\n")
        assertEquals(0.5f, v.fraction!!, 0.001f)
        assertEquals("Stopping containers", v.phase)
    }

    @Test fun withoutCountersItemsAgainstTheExpectedNumberGiveTheBar() {
        val text = " ✔ Container a  Stopped\n ⠿ Container b  Stopping\n"
        assertEquals(0.25f, ComposeProgress.parse(text, expected = 4).fraction!!, 0.001f)
        // Without an expected number the seen items are the denominator.
        assertEquals(0.5f, ComposeProgress.parse(text).fraction!!, 0.001f)
    }

    @Test fun newerComposeWithoutCountersStillShowsItsItems() {
        val v = ComposeProgress.parse(" ✔ Image nginx:latest Pulled\n ✔ Network proj_default Created\n ⠿ Container proj-web-1 Starting\n", twoPhase = false, expected = 3)
        assertEquals(listOf("nginx:latest", "proj_default", "proj-web-1"), v.items.map { it.name })
        assertEquals("Starting containers", v.phase)
        assertEquals(2f / 3f, v.fraction!!, 0.001f)
    }

    @Test fun layerLinesAreDetailNotItemsAndLoseTheirBar() {
        val v = ComposeProgress.parse("[+] Pulling 0/1\n ⠿ web Pulling\n   a1b2c3d4e5f6 Downloading [==>      ]  1.2MB/30MB\n")
        assertEquals(1, v.items.size)
        assertEquals("Downloading 1.2MB/30MB", v.detail)
    }

    @Test fun anErrorItemIsFailed() {
        val v = ComposeProgress.parse(" ✘ Container proj-web-1  Error\n")
        assertEquals(StepState.Failed, v.items.single().state)
    }

    @Test fun noRecognisableOutputMeansNoPercentage() {
        assertNull(ComposeProgress.parse("").fraction)
        assertNull(ComposeProgress.parse("something compose never printed before\n").fraction)
        assertEquals("something compose never printed before", ComposeProgress.parse("something compose never printed before\n").detail)
    }

    @Test fun onlyTheNewestEightItemsAreKept() {
        val text = (1..12).joinToString("\n") { " ✔ Container c$it  Started" }
        val v = ComposeProgress.parse(text)
        assertEquals(8, v.items.size)
        assertEquals("c12", v.items.last().name)
        assertEquals("c5", v.items.first().name)
    }

    @Test fun servicePullLinesWithoutAnImagePrefixAreRecognised() {
        val v = ComposeProgress.parse(" web Pulling\n ✔ web Pulled\n")
        assertEquals(StepState.Done, v.items.single().state)
        assertEquals("Image", v.items.single().kind)
    }
}
