package com.feerlessleadr.dockge.notify

import com.feerlessleadr.dockge.core.Instance
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** What an alert is about, guessed from the text Dockge sends through Apprise (it sends only a title and a body). */
enum class AlertKind(
    /** Shown on the lock screen: generic on purpose, no stack or container names. */
    val publicTitle: String,
) {
    CrashLoop("Dockge: a container keeps restarting"),
    Unhealthy("Dockge: a container is unhealthy"),
    ImageUpdate("Dockge: image updates available"),
    Other("Dockge: new alert"),
}

object AlertClassifier {
    fun kind(title: String, body: String): AlertKind {
        val t = title.lowercase()
        return when {
            "crash loop" in t || "crashloop" in t -> AlertKind.CrashLoop
            "healthcheck" in t || "unhealthy" in t -> AlertKind.Unhealthy
            "update" in t && "dockge-enhanced" !in t -> AlertKind.ImageUpdate
            else -> AlertKind.Other
        }
    }
}

/** Where a tap on an alert goes. [stackName] is null when the app could not tell which stack it was about. */
data class AlertTarget(val instanceId: String, val endpoint: String = "", val stackName: String? = null)

object AlertRouting {
    /**
     * Best effort, by design: the message names neither the instance nor reliably the stack.
     * Instance: the saved address (or at least its host) appears in the text, otherwise the only saved instance.
     * Stack: exactly one known stack name appears in the text as a whole word. Several or none: the instance only.
     * Returns null when no instance can be chosen (the app then opens its instance list).
     */
    fun resolve(
        message: NtfyMessage,
        instances: List<Instance>,
        stacksOf: (instanceId: String) -> List<Pair<String, String>>,
    ): AlertTarget? {
        val text = message.title + "\n" + message.message
        val lower = text.lowercase()
        val instance = instances.firstOrNull { lower.contains(it.baseUrl.lowercase()) }
            ?: instances.firstOrNull { host(it.baseUrl)?.let { h -> lower.contains(h) } == true }
            ?: instances.singleOrNull()
            ?: return null
        val matches = stacksOf(instance.id).filter { (_, name) -> name.isNotEmpty() && containsWord(text, name) }
        val distinct = matches.distinctBy { it.second }
        val only = distinct.singleOrNull() ?: return AlertTarget(instance.id)
        return AlertTarget(instance.id, only.first, only.second)
    }

    private fun host(baseUrl: String): String? =
        runCatching { java.net.URI(baseUrl).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

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
