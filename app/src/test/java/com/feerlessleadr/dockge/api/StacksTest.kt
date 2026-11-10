package com.feerlessleadr.dockge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Samples follow docs/dockge-api.md part B (from the fork's source). Not live captures. */
class StacksTest {
    private fun args(vararg json: String): List<JsonElement> = json.map { Json.parseToJsonElement(it) }
    private fun j(s: String) = Json.parseToJsonElement(s)

    private val stackListPush = args(
        "\"stackList\"",
        """{"ok":true,"endpoint":"","stackList":{
            "web":{"name":"web","displayName":"Web Front","status":3,"isManagedByDockge":true,"isExternal":false,
                   "composeFileName":"compose.yaml","lastUpdated":"2026-11-01T10:00:00.000Z","tags":[]},
            "Db":{"name":"Db","displayName":"","status":4,"isManagedByDockge":true,"composeFileName":"compose.yaml","lastUpdated":null},
            "found":{"name":"found","status":3,"isManagedByDockge":false,"isExternal":true},
            "weird":{"name":"weird","status":99}
        }}""",
    )

    @Test fun stackListIsParsedAndSortedIgnoringCase() {
        val stacks = StackParser.parseStackList(stackListPush)!!.stacks
        assertEquals(listOf("Db", "found", "web", "weird"), stacks.map { it.name })
        val web = stacks.first { it.name == "web" }
        assertEquals("Web Front", web.title)
        assertEquals(StackStatus.Running, web.status)
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), web.lastUpdated)
        assertEquals("Db", stacks.first { it.name == "Db" }.title)
        assertNull(stacks.first { it.name == "Db" }.lastUpdated)
    }

    @Test fun unmanagedExternalAndUnknownStatus() {
        val stacks = StackParser.parseStackList(stackListPush)!!.stacks
        val found = stacks.first { it.name == "found" }
        assertFalse(found.isManaged)
        assertTrue(found.isExternal)
        assertEquals(StackStatus.Unknown, stacks.first { it.name == "weird" }.status)
        assertTrue(stacks.first { it.name == "weird" }.isManaged)
    }

    @Test fun statusCodes() {
        assertEquals(StackStatus.Draft, StackStatus.fromCode(1))
        assertEquals(StackStatus.Paused, StackStatus.fromCode(5))
        assertEquals(StackStatus.Unknown, StackStatus.fromCode(null))
    }

    @Test fun otherEventsAndBadShapesAreIgnored() {
        assertNull(StackParser.parseStackList(args("\"terminalWrite\"", "\"x\"")))
        assertNull(StackParser.parseStackList(args("\"stackList\"")))
        assertNull(StackParser.parseStackList(args("\"stackList\"", "\"oops\"")))
        assertNull(StackParser.parseStackList(args("\"stackList\"", """{"ok":true}""")))
        assertEquals(emptyList<Stack>(), StackParser.parseStackList(args("\"stackList\"", """{"ok":true,"stackList":{}}"""))!!.stacks)
    }

    @Test fun serviceStatus() {
        val reply = j(
            """{"ok":true,"serviceStatusList":{
                "web":{"state":"healthy","ports":["0.0.0.0:8080->80/tcp"],"startedAt":"2026-11-08T08:00:00Z"},
                "db":{"state":"exited","ports":[],"startedAt":null}},
               "lastUpdated":"2026-11-01T10:00:00Z","lastStartedAt":null,"lowPowerMode":false}""",
        )
        val (services, lastUpdated, lastStarted) = StackParser.parseServiceStatus(reply)
        assertEquals(listOf("db", "web"), services.map { it.service })
        assertEquals("healthy", services.last().state)
        assertEquals(listOf("0.0.0.0:8080->80/tcp"), services.last().ports)
        assertEquals(Instant.parse("2026-11-08T08:00:00Z"), services.last().startedAt)
        assertNull(services.first().startedAt)
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), lastUpdated)
        assertNull(lastStarted)
    }

    @Test fun containers() {
        val reply = j(
            """{"ok":true,"instances":[
              {"id":"abcdef123456","name":"web-1","service":"web","state":"running","health":"healthy","image":"nginx:latest",
               "ports":"0.0.0.0:8080->80/tcp, :::8080->80/tcp","createdAt":"x","runningFor":"3 hours ago"},
              {"id":"fedcba654321","name":"db-1","service":"db","state":"exited","health":"","image":"postgres:16","ports":"","createdAt":"x","runningFor":""},
              {"name":"no id"}]}""",
        )
        val containers = StackParser.parseContainers(reply)
        assertEquals(listOf("db-1", "web-1"), containers.map { it.name })
        assertEquals("healthy", containers.last().health)
        assertEquals("nginx:latest", containers.last().image)
    }

    @Test fun errorRepliesThrowWithTheServerMessage() {
        try {
            StackParser.parseContainers(j("""{"ok":false,"msg":"Stack not found","msgi18n":true}"""))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("Stack not found", e.message)
        }
        try {
            StackParser.parseServiceStatus(j("\"nope\""))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}

class StacksFederationTest {
    private fun args(vararg json: String): List<JsonElement> = json.map { Json.parseToJsonElement(it) }

    @Test fun stackListCarriesTheEndpointOfItsServer() {
        val local = StackParser.parseStackList(args("\"stackList\"", """{"ok":true,"endpoint":"","stackList":{"a":{"name":"a","status":3}}}"""))!!
        val agent = StackParser.parseStackList(args("\"stackList\"", """{"ok":true,"endpoint":"host2:5001","stackList":{"a":{"name":"a","status":4}}}"""))!!
        assertEquals("", local.endpoint)
        assertEquals("host2:5001", agent.endpoint)
        assertEquals("host2:5001", agent.stacks.single().endpoint)
    }

    @Test fun missingEndpointMeansTheInstanceItself() {
        val push = StackParser.parseStackList(args("\"stackList\"", """{"ok":true,"stackList":{}}"""))!!
        assertEquals("", push.endpoint)
    }

    @Test fun agentList() {
        val list = StackParser.parseAgentList(
            args("""{"ok":true,"agentList":{"":{"url":"","displayName":"Main"},"h2:5001":{"url":"https://h2","displayName":"","endpoint":"h2:5001"}}}"""),
        )!!
        assertEquals("Main", list.getValue("").displayName)
        assertEquals("https://h2", list.getValue("h2:5001").url)
        assertNull(StackParser.parseAgentList(args("""{"ok":true}""")))
    }

    @Test fun agentStatus() {
        assertEquals("h2:5001" to "offline", StackParser.parseAgentStatus(args("""{"endpoint":"h2:5001","status":"offline"}""")))
        assertNull(StackParser.parseAgentStatus(args("""{"status":"offline"}""")))
    }
}
