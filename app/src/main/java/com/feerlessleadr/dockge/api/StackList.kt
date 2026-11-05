package com.feerlessleadr.dockge.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The server pushes `on("agent", "stackList", {ok, stackList: {<name>: {...}}, endpoint})`.
 * M1 only needs the names, to prove that a login works. The full stack model comes in M3.
 */
object StackListParser {
    /** Returns the stack names, or null when [args] is not a stackList push. */
    fun names(args: List<JsonElement>): List<String>? {
        if (args.size < 2) return null
        if ((args[0] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull != "stackList") return null
        val payload = args[1] as? JsonObject ?: return null
        val list = payload["stackList"] as? JsonObject ?: return null
        return list.keys.sorted()
    }
}
