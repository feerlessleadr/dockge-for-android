package com.feerlessleadr.dockge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StackListParserTest {
    private fun args(vararg json: String): List<JsonElement> = json.map { Json.parseToJsonElement(it) }

    @Test fun readsNamesSorted() {
        val a = args("\"stackList\"", """{"ok":true,"stackList":{"web":{"status":3},"db":{"status":4}},"endpoint":""}""")
        assertEquals(listOf("db", "web"), StackListParser.names(a))
    }

    @Test fun emptyListIsEmptyNotNull() {
        assertEquals(emptyList<String>(), StackListParser.names(args("\"stackList\"", """{"ok":true,"stackList":{}}""")))
    }

    @Test fun otherEventsAreIgnored() {
        assertNull(StackListParser.names(args("\"terminalWrite\"", "\"x\"")))
        assertNull(StackListParser.names(listOf(JsonPrimitive("stackList"))))
        assertNull(StackListParser.names(args("\"stackList\"", "\"oops\"")))
    }
}
