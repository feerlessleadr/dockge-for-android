package com.feerlessleadr.dockge.core

import com.feerlessleadr.dockge.api.ContainerInstance
import com.feerlessleadr.dockge.api.StackDetail
import java.time.Duration
import java.time.Instant

object Uptime {
    /** "45s", "12m", "3h 5m", "4d 2h". A missing start time (never started) gives "-". */
    fun format(startedAt: Instant?, now: Instant): String {
        if (startedAt == null) return "-"
        val d = Duration.between(startedAt, now).let { if (it.isNegative) Duration.ZERO else it }
        val days = d.toDays()
        val hours = d.toHours() % 24
        val minutes = d.toMinutes() % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            d.toHours() > 0 -> "${d.toHours()}h ${minutes}m"
            d.toMinutes() > 0 -> "${d.toMinutes()}m"
            else -> "${d.seconds}s"
        }
    }
}

object PortFormat {
    /**
     * "0.0.0.0:8080->80/tcp" becomes "8080 -> 80/tcp". IPv6 duplicates (":::8080->80/tcp") and entries
     * without a published mapping are dropped.
     */
    fun simplify(ports: List<String>): List<String> = ports
        .map { it.trim() }
        .filter { it.contains("->") && !it.startsWith(":::") && !it.startsWith("[::]") }
        .map { entry ->
            val (host, container) = entry.split("->", limit = 2)
            "${host.substringAfterLast(':')} -> $container"
        }
        .distinct()

    /** The container list gives one comma separated string. */
    fun simplify(raw: String): List<String> = simplify(raw.split(","))
}

/** What the detail screen shows for one container: the container joined with the start time of its service. */
data class ContainerView(
    val id: String,
    val name: String,
    val service: String,
    val image: String,
    val state: String,
    val health: String,
    val ports: List<String>,
    val startedAt: Instant?,
) {
    val isRunning: Boolean get() = state == "running"
}

fun StackDetail.containerViews(): List<ContainerView> {
    val byService = services.associateBy { it.service }
    val fromContainers = containers.map { c: ContainerInstance ->
        val service = byService[c.service]
        ContainerView(
            id = c.id,
            name = c.name,
            service = c.service,
            image = c.image,
            state = c.state,
            health = c.health,
            ports = service?.ports?.let(PortFormat::simplify)?.takeIf { it.isNotEmpty() } ?: PortFormat.simplify(c.ports),
            startedAt = service?.startedAt?.takeIf { c.state == "running" },
        )
    }
    // A service that has no container row (should not happen) is still shown so nothing silently disappears.
    val seen = containers.map { it.service }.toSet()
    val orphans = services.filter { it.service !in seen }.map {
        ContainerView(
            id = "service:${it.service}", name = it.service, service = it.service, image = "", state = it.state,
            health = "", ports = PortFormat.simplify(it.ports), startedAt = it.startedAt,
        )
    }
    return fromContainers + orphans
}
