package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.CallTimeoutException
import com.feerlessleadr.dockge.core.InstanceColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StackDataTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun stackListPush(vararg names: String) = j(
        """{"ok":true,"endpoint":"","stackList":{${names.joinToString(",") { "\"$it\":{\"name\":\"$it\",\"status\":3}" }}}}""",
    )

    @Test fun storeKeepsTheLatestStackList() = runTest {
        val socket = FakeSocket()
        val store = StackListStore(socket, backgroundScope)
        runCurrent()
        assertNull(store.servers.value)
        socket.push("agent", JsonPrimitive("stackList"), stackListPush("a", "b"))
        runCurrent()
        assertEquals(listOf("a", "b"), store.servers.value!!.single().stacks.map { it.name })
        socket.push("agent", JsonPrimitive("terminalWrite"), JsonPrimitive("x"))
        socket.push("agent", JsonPrimitive("stackList"), stackListPush("c"))
        runCurrent()
        assertEquals(listOf("c"), store.servers.value!!.single().stacks.map { it.name })
    }

    private fun pushFor(endpoint: String, vararg names: String) = j(
        """{"ok":true,"endpoint":"$endpoint","stackList":{${names.joinToString(",") { "\"$it\":{\"name\":\"$it\",\"status\":3}" }}}}""",
    )

    /** The bug seen on a federated instance: lists of linked servers replaced each other and the counts flipped. */
    @Test fun listsOfLinkedServersDoNotOverwriteEachOther() = runTest {
        val socket = FakeSocket()
        val store = StackListStore(socket, backgroundScope)
        runCurrent()
        socket.push(
            "agentList",
            j("""{"ok":true,"agentList":{"":{"displayName":"Main"},"h2:5001":{"displayName":"Media","url":"https://h2"},"h3:5001":{"displayName":"","url":"https://h3"}}}"""),
        )
        socket.push("agent", JsonPrimitive("stackList"), pushFor("", "a"))
        socket.push("agent", JsonPrimitive("stackList"), pushFor("h2:5001", "x", "y"))
        socket.push("agent", JsonPrimitive("stackList"), pushFor("h3:5001", "z"))
        socket.push("agent", JsonPrimitive("stackList"), pushFor("", "a"))
        runCurrent()
        val servers = store.servers.value!!
        // Linked servers are sorted by their display name, so the unnamed one (shown as its address) comes first.
        assertEquals(listOf("", "h3:5001", "h2:5001"), servers.map { it.endpoint })
        assertEquals(listOf("Main", "https://h3", "Media"), servers.map { it.name })
        assertEquals(listOf(1, 1, 2), servers.map { it.stacks.size })
        assertTrue(servers.first().isLocal)
    }

    @Test fun unnamedLocalServerGetsADefaultNameAndStatusIsTracked() = runTest {
        val socket = FakeSocket()
        val store = StackListStore(socket, backgroundScope)
        runCurrent()
        socket.push("agent", JsonPrimitive("stackList"), pushFor("", "a"))
        socket.push("agent", JsonPrimitive("stackList"), pushFor("h2:5001", "x"))
        socket.push("agentStatus", j("""{"endpoint":"h2:5001","status":"offline"}"""))
        runCurrent()
        val servers = store.servers.value!!
        assertEquals("This server", servers[0].name)
        assertEquals("h2:5001", servers[1].name)
        assertEquals("offline", servers[1].status)
    }

    @Test fun removedServerIsDropped() = runTest {
        val socket = FakeSocket()
        val store = StackListStore(socket, backgroundScope)
        runCurrent()
        socket.push("agent", JsonPrimitive("stackList"), pushFor("", "a"))
        socket.push("agent", JsonPrimitive("stackList"), pushFor("h2:5001", "x"))
        runCurrent()
        assertEquals(2, store.servers.value!!.size)
        socket.push("agentList", j("""{"ok":true,"agentList":{"":{"displayName":""}}}"""))
        runCurrent()
        assertEquals(listOf(""), store.servers.value!!.map { it.endpoint })
    }

    @Test fun detailLoaderAddressesTheRequestedServer() = runTest {
        val socket = FakeSocket()
        socket.handler = { _, args ->
            assertEquals("h2:5001", args[0].jsonPrimitive.content)
            when (args[1].jsonPrimitive.content) {
                "serviceStatusList" -> j("""{"ok":true,"serviceStatusList":{}}""")
                else -> j("""{"ok":true,"instances":[]}""")
            }
        }
        StackDetailLoader.load(socket, "web", "h2:5001").getOrThrow()
        assertEquals(2, socket.calls.size)
    }

    @Test fun detailLoaderAsksBothReadOnlyCallsForTheStack() = runTest {
        val socket = FakeSocket()
        socket.handler = { event, args ->
            assertEquals("agent", event)
            assertEquals("", args[0].jsonPrimitive.content)
            assertEquals("web", args[2].jsonPrimitive.content)
            when (args[1].jsonPrimitive.content) {
                "serviceStatusList" -> j("""{"ok":true,"serviceStatusList":{"web":{"state":"running","ports":[],"startedAt":null}}}""")
                "containerInstances" -> j(
                    """{"ok":true,"instances":[{"id":"abcdef123456","name":"web-1","service":"web","state":"running","health":"","image":"nginx","ports":"","runningFor":""}]}""",
                )
                else -> error("unexpected ${args[1]}")
            }
        }
        val detail = StackDetailLoader.load(socket, "web").getOrThrow()
        assertEquals(1, detail.containers.size)
        assertEquals(1, detail.services.size)
        assertEquals(setOf("serviceStatusList", "containerInstances"), socket.calls.map { it.second[1].jsonPrimitive.content }.toSet())
    }

    @Test fun detailLoaderReportsServerErrors() = runTest {
        val socket = FakeSocket()
        socket.handler = { _, _ -> j("""{"ok":false,"msg":"Stack not found"}""") }
        val result = StackDetailLoader.load(socket, "gone")
        assertTrue(result.isFailure)
        assertEquals("Stack not found", result.exceptionOrNull()!!.message)
    }

    @Test fun detailLoaderReportsTimeouts() = runTest {
        val socket = FakeSocket()
        socket.handler = { e, _ -> throw CallTimeoutException(e) }
        val result = StackDetailLoader.load(socket, "web")
        assertTrue(result.exceptionOrNull() is CallTimeoutException)
    }

    @Test fun managerExposesStackListsPerInstance() = runTest {
        val vault = MemoryVault()
        val repo = InstanceRepository(MemoryInstanceStore(), vault)
        val sockets = mutableMapOf<String, FakeSocket>()
        val manager = SessionManager(repo, vault, backgroundScope) { url ->
            FakeSocket().also {
                it.handler = { _, _ -> j("""{"ok":true}""") }
                sockets[url.value] = it
            }
        }
        val a = repo.add("A", "https://a.example.com", InstanceColors.palette[0]).getOrThrow()
        val b = repo.add("B", "https://b.example.com", InstanceColors.palette[0]).getOrThrow()
        manager.start(); runCurrent()
        sockets.getValue("https://a.example.com").push("agent", JsonPrimitive("stackList"), stackListPush("x", "y"))
        runCurrent()
        assertEquals(listOf("x", "y"), manager.stackLists.value[a.id]!!.single().stacks.map { it.name })
        assertNull(manager.stackLists.value[b.id])
        repo.delete(a.id); runCurrent()
        assertNull(manager.stackLists.value[a.id])
    }
}
