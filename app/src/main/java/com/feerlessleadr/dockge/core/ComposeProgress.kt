package com.feerlessleadr.dockge.core

enum class StepState { Working, Done, Failed }

/** One thing compose is doing: an image pull, a container, a network or a volume. */
data class ProgressItem(val kind: String, val name: String, val verb: String, val state: StepState)

/** What the progress screen shows, worked out from the raw compose output. [fraction] null means "cannot tell". */
data class ProgressView(
    val fraction: Float?,
    val phase: String,
    /** Most recent first appearance last; at most [ComposeProgress.MAX_ITEMS]. */
    val items: List<ProgressItem>,
    /** The newest line of output, tidied, or null. */
    val detail: String?,
)

/**
 * Reads the terminal output of `docker compose pull`, `up`, `stop`, `down` and `restart`.
 *
 * Compose redraws its progress block in place with cursor movements. The app strips those, so the text holds the
 * block many times over, each copy newer than the last. So everything here takes the LAST occurrence: the last
 * `[+] Pulling 5/12` header, and the last verb of each item. Formats differ a little between compose versions, so
 * the parser is tolerant: no match just means no percentage (the bar stays indeterminate), never a wrong crash.
 */
object ComposeProgress {
    const val MAX_ITEMS = 8
    private const val RUNNING_CAP = 0.97f

    private val header = Regex("""\[\+]\s+([A-Za-z]+)\s+(\d+)/(\d+)""")
    private val marks = """[✔✘✓✗⠿⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏!]"""
    private val item = Regex("""^\s*(?:$marks\s+)?(Image|Container|Network|Volume)\s+(\S+)\s+(\S.*?)\s*$""")
    private val layer = Regex("""^\s*([0-9a-f]{12})\s+(\S.*?)\s*$""")
    private val serviceLine = Regex("""^\s*(?:$marks\s+)?([A-Za-z0-9][\w.-]*)\s+(Pulling|Pulled)\s*$""")
    private val bar = Regex("""\[[=>\s-]*]""")

    private val done = setOf("pulled", "created", "recreated", "started", "stopped", "removed", "running", "healthy", "restarted", "exists")
    private val failed = setOf("error", "failed")

    private fun stateOf(verb: String): StepState {
        val v = verb.lowercase().trim().substringBefore(' ')
        return when {
            v in failed -> StepState.Failed
            v in done -> StepState.Done
            else -> StepState.Working
        }
    }

    /**
     * [twoPhase] is true for an update (pull, then recreate): the bar then fills to half while pulling and to the
     * end while recreating. [expected] is how many items a finished run would show (used only when compose prints
     * no counters), or null when unknown.
     */
    fun parse(text: String, twoPhase: Boolean = false, expected: Int? = null): ProgressView {
        val lines = text.lines()
        val items = LinkedHashMap<String, ProgressItem>()
        var lastHeader: Triple<String, Int, Int>? = null
        var pullHeaderSeen = false
        var lastDetail: String? = null

        for (raw in lines) {
            if (raw.isBlank()) continue
            val h = header.find(raw)
            if (h != null) {
                val parsed = Triple(h.groupValues[1], h.groupValues[2].toInt(), h.groupValues[3].toInt())
                lastHeader = parsed
                if (parsed.first.equals("Pulling", ignoreCase = true)) pullHeaderSeen = true
                continue
            }
            val layerMatch = layer.matchEntire(raw)
            if (layerMatch != null) {
                lastDetail = tidy(layerMatch.groupValues[2])
                continue
            }
            val m = item.matchEntire(raw)
            if (m != null) {
                val kind = m.groupValues[1]
                val name = m.groupValues[2]
                val verb = m.groupValues[3].trim()
                if (kind == "Image" && verb.contains("Pull", ignoreCase = true)) pullHeaderSeen = true
                items["$kind $name"] = ProgressItem(kind, name, verb, stateOf(verb))
                lastDetail = tidy("$kind $name $verb")
                continue
            }
            val s = serviceLine.matchEntire(raw)
            if (s != null) {
                pullHeaderSeen = true
                items["Image ${s.groupValues[1]}"] = ProgressItem("Image", s.groupValues[1], s.groupValues[2], stateOf(s.groupValues[2]))
                lastDetail = tidy(raw)
                continue
            }
            lastDetail = tidy(raw)
        }

        val list = items.values.toList().takeLast(MAX_ITEMS)
        val fraction = fraction(lastHeader, items.values.toList(), twoPhase, expected, pullHeaderSeen)
        return ProgressView(fraction, phase(lastHeader, items.values.toList()), list, lastDetail)
    }

    private fun fraction(
        header: Triple<String, Int, Int>?,
        all: List<ProgressItem>,
        twoPhase: Boolean,
        expected: Int?,
        pulled: Boolean,
    ): Float? {
        val part: Float?
        var pulling = false
        if (header != null && header.third > 0) {
            part = (header.second.toFloat() / header.third).coerceIn(0f, 1f)
            pulling = header.first.equals("Pulling", ignoreCase = true)
        } else if (all.isNotEmpty()) {
            val finished = all.count { it.state == StepState.Done }
            val denominator = maxOf(expected ?: 0, all.size).coerceAtLeast(1)
            part = (finished.toFloat() / denominator).coerceIn(0f, 1f)
            pulling = all.lastOrNull { it.state == StepState.Working }?.let { it.kind == "Image" } ?: false
        } else {
            return null
        }
        val overall = if (twoPhase) {
            if (pulling) part * 0.5f else if (pulled) 0.5f + part * 0.5f else part
        } else {
            part
        }
        return overall.coerceAtMost(RUNNING_CAP)
    }

    private fun phase(header: Triple<String, Int, Int>?, all: List<ProgressItem>): String {
        val verb = all.lastOrNull { it.state == StepState.Working }?.verb?.lowercase()?.substringBefore(" ") ?: header?.first?.lowercase()
        return when (verb) {
            "pulling" -> "Pulling images"
            "recreate", "recreating", "creating", "created", "recreated" -> "Recreating containers"
            "starting", "started", "running" -> "Starting containers"
            "stopping", "stopped" -> "Stopping containers"
            "restarting", "restarted" -> "Restarting containers"
            "removing", "removed" -> "Removing containers"
            "waiting", "healthy" -> "Waiting for containers"
            null -> "Working"
            else -> "Working"
        }
    }

    private fun tidy(line: String): String =
        line.replace(bar, "").replace(Regex("""\s+"""), " ").trim().take(90)
}
