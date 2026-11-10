package com.feerlessleadr.dockge.core

/**
 * Turns raw pty output (what `docker compose` writes to the server's terminal) into plain text for a small screen:
 * drops ANSI escape sequences and other control characters, and lets a carriage return replace the current line,
 * which is how compose draws its progress lines. Chunks may split an escape sequence anywhere.
 * Keeps only the last [maxChars] characters so a chatty pull cannot grow without limit.
 */
class TerminalBuffer(private val maxChars: Int = 64 * 1024) {
    private enum class Mode { Normal, Escape, Csi }

    private val sb = StringBuilder()
    private var lineStart = 0
    private var overwrite = false
    private var mode = Mode.Normal

    fun append(chunk: String) {
        for (ch in chunk) {
            when (mode) {
                Mode.Escape -> mode = if (ch == '[') Mode.Csi else Mode.Normal
                Mode.Csi -> if (ch in '@'..'~') mode = Mode.Normal
                Mode.Normal -> when {
                    ch == '\u001B' -> mode = Mode.Escape
                    ch == '\n' -> { sb.append('\n'); lineStart = sb.length; overwrite = false }
                    ch == '\r' -> overwrite = true
                    ch < ' ' && ch != '\t' -> Unit
                    else -> {
                        if (overwrite) { sb.setLength(lineStart); overwrite = false }
                        sb.append(ch)
                    }
                }
            }
        }
        if (sb.length > maxChars) {
            val cut = sb.length - maxChars
            sb.delete(0, cut)
            lineStart = (lineStart - cut).coerceAtLeast(0)
        }
    }

    fun text(): String = sb.toString()
}
