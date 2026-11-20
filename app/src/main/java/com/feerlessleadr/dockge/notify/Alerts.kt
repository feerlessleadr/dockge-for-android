package com.feerlessleadr.dockge.notify

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** What an alert is about. One kind per thing the app watches. */
enum class AlertKind(
    /** Shown on the lock screen: generic on purpose, no stack or container names. */
    val publicTitle: String,
) {
    ImageUpdate("Dockge: image update available"),
    CrashLoop("Dockge: a container keeps restarting"),
    Unhealthy("Dockge: a container is unhealthy"),
    Backup("Dockge: backup problem"),
    Security("Dockge: security finding"),
}

/** Where a tap on an alert goes. [stackName] is null when the alert is not about one stack. */
data class AlertTarget(val instanceId: String, val endpoint: String = "", val stackName: String? = null)

/** One alert the app raises itself. [key] identifies the event, so the same event is never shown twice. */
data class Alert(
    val key: String,
    val kind: AlertKind,
    val title: String,
    val text: String,
    val target: AlertTarget,
    /** Unix milliseconds. */
    val time: Long,
)

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
