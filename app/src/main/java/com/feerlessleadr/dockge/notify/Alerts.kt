package com.feerlessleadr.dockge.notify

import com.feerlessleadr.dockge.core.Instance
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What an alert is about, guessed from the title Dockge sends through Apprise (it sends only a title and a body).
 * One kind per notification dockge-enhanced can send.
 */
enum class AlertKind(
    /** Shown on the lock screen: generic on purpose, no stack or container names. */
    val publicTitle: String,
) {
    CrashLoop("Dockge: a container keeps restarting"),
    Unhealthy("Dockge: a container is unhealthy"),
    ImageUpdate("Dockge: image updates"),
    DockgeUpdate("Dockge: Dockge itself has an update"),
    Backup("Dockge: backup report"),
    Security("Dockge: security report"),
    Cleanup("Dockge: cleanup report"),
    Federation("Dockge: a linked server has a problem"),
    Other("Dockge: new alert"),
}

object AlertClassifier {
    private fun has(text: String, vararg words: String) = words.any { it in text }

    /**
     * Matches the English, French, Spanish and Chinese titles of the fork (German titles fall back to [AlertKind.Other]
     * where they share no word). Order matters: the specific kinds come before the generic "update" words.
     */
    fun kind(title: String, body: String): AlertKind {
        val t = title.lowercase()
        return when {
            has(t, "crash loop") -> AlertKind.CrashLoop
            has(t, "healthcheck", "unhealthy") -> AlertKind.Unhealthy
            has(t, "security alert", "security report", "alerte sécurité", "rapport de sécurité", "alerta de seguridad", "informe de seguridad", "安全警报", "安全报告") -> AlertKind.Security
            has(t, "backup", "copia de dockge", "备份") -> AlertKind.Backup
            has(t, "cleanup", "nettoyage", "bereinigung", "limpieza", "清理") -> AlertKind.Cleanup
            has(t, "federation", "fédération", "federación", "联邦", "sqlite") -> AlertKind.Federation
            has(t, "dockge-enhanced") && has(t, "updat", "mise à jour", "actualiz", "更新") -> AlertKind.DockgeUpdate
            has(t, "update(s)", "auto-updated", "mise(s) à jour", "automatiquement", "actualización(es)", "actualizada", "个更新", "已自动更新") -> AlertKind.ImageUpdate
            else -> AlertKind.Other
        }
    }
}

/** Shows the same event once when it arrives through several sources (Dockge can send to several services). */
class AlertDeduper(
    private val windowMs: Long = 2 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seen = LinkedHashMap<String, Long>()

    /** True the first time this title and text is seen within the window; false for a copy. */
    fun firstSighting(title: String, message: String): Boolean {
        val now = clock()
        seen.entries.removeAll { now - it.value > windowMs }
        val key = (title + "\u0000" + message).lowercase().replace(Regex("""\s+"""), " ").trim()
        if (key in seen) return false
        seen[key] = now
        return true
    }
}

/** Where a tap on an alert goes. [stackName] is null when the app could not tell which stack it was about. */
data class AlertTarget(val instanceId: String, val endpoint: String = "", val stackName: String? = null)

object AlertRouting {
    // The image-update body lists each stack as "Stack : **name**" (the label is translated in the fork).
    private val stackLine = Regex("""(?:Stack|堆栈)\s*[:：]\s*\**([A-Za-z0-9_.-]+)""")

    /**
     * Best effort, by design: the message names neither the instance nor, reliably, the stack.
     * Instance: the saved address (or at least its host) appears in the text, otherwise the only saved instance.
     * Stack: for image updates the "Stack : name" lines of the body, otherwise a known stack name that appears as a
     * whole word. Exactly one distinct stack is opened; several or none open the instance. Backup, security,
     * cleanup, federation and Dockge-update alerts open the instance only.
     * Returns null when no instance can be chosen (the app then opens its instance list).
     */
    fun resolve(
        message: AlertMessage,
        kind: AlertKind,
        instances: List<Instance>,
        stacksOf: (instanceId: String) -> List<Pair<String, String>>,
    ): AlertTarget? {
        val text = message.title + "\n" + message.message
        val lower = text.lowercase()
        val instance = instances.firstOrNull { lower.contains(it.baseUrl.lowercase()) }
            ?: instances.firstOrNull { host(it.baseUrl)?.let { h -> lower.contains(h) } == true }
            ?: instances.singleOrNull()
            ?: return null
        val known = stacksOf(instance.id)
        val name: String = when (kind) {
            AlertKind.ImageUpdate -> stackLine.findAll(message.message).map { it.groupValues[1] }.distinct().toList().singleOrNull()
            AlertKind.CrashLoop, AlertKind.Unhealthy, AlertKind.Other ->
                known.filter { (_, n) -> n.isNotEmpty() && containsWord(text, n) }.distinctBy { it.second }.singleOrNull()?.second
            else -> null
        } ?: return AlertTarget(instance.id)
        val endpoint = known.firstOrNull { it.second == name }?.first ?: ""
        return AlertTarget(instance.id, endpoint, name)
    }

    private fun host(baseUrl: String): String? =
        runCatching { URI(baseUrl).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Stack names are made of letters, digits, - and _, so a name inside a longer name does not count. */
    internal fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from, ignoreCase = true)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + word.length)
            if (!isNameChar(before) && !isNameChar(after)) return true
            from = i + 1
        }
    }

    private fun isNameChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '-' || c == '_')
}

/** `dockge://instance/<id>` or `dockge://instance/<id>/stack/<name>?endpoint=<server>`. */
object DeepLink {
    const val SCHEME = "dockge"

    // java.net only (not android.net.Uri) so this runs in plain JVM unit tests.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    fun build(target: AlertTarget): String {
        val base = "$SCHEME://instance/${enc(target.instanceId)}"
        val name = target.stackName ?: return base
        val endpoint = if (target.endpoint.isEmpty()) "" else "?endpoint=${enc(target.endpoint)}"
        return "$base/stack/${enc(name)}$endpoint"
    }

    fun parse(link: String?): AlertTarget? {
        val uri = try {
            URI(link ?: return null)
        } catch (e: Exception) {
            return null
        }
        if (uri.scheme != SCHEME || uri.host != "instance") return null
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map(::dec)
        val id = segments.getOrNull(0) ?: return null
        if (segments.size >= 3 && segments[1] == "stack") {
            val endpoint = (uri.rawQuery ?: "").split('&')
                .firstOrNull { it.startsWith("endpoint=") }?.removePrefix("endpoint=")?.let(::dec) ?: ""
            return AlertTarget(id, endpoint, segments[2])
        }
        return AlertTarget(id)
    }
}
