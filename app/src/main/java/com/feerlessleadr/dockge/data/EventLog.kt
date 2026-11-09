package com.feerlessleadr.dockge.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A small in-memory log of what one instance's connection did (state changes and the shape of the pushes it
 * received). It exists so odd behavior can be diagnosed from the phone. It is never written to disk, never sent
 * anywhere, and holds no tokens, passwords or message bodies: only states, endpoint names, counts and a few stack names.
 */
class EventLog(
    private val capacity: Int = 80,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val format = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Newest first. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(text: String) {
        val line = "${format.format(Instant.ofEpochMilli(clock()))} $text"
        _lines.update { (listOf(line) + it).take(capacity) }
    }
}
