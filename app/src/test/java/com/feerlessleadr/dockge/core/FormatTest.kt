package com.feerlessleadr.dockge.core

import com.feerlessleadr.dockge.api.ContainerInstance
import com.feerlessleadr.dockge.api.ServiceStatus
import com.feerlessleadr.dockge.api.StackDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FormatTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private fun ago(seconds: Long) = now.minusSeconds(seconds)

    @Test fun uptime() {
        assertEquals("-", Uptime.format(null, now))
        assertEquals("45s", Uptime.format(ago(45), now))
        assertEquals("12m", Uptime.format(ago(12 * 60 + 30), now))
        assertEquals("3h 5m", Uptime.format(ago(3 * 3600 + 5 * 60), now))
        assertEquals("4d 2h", Uptime.format(ago(4 * 86400 + 2 * 3600 + 59), now))
        assertEquals("0s", Uptime.format(now.plusSeconds(30), now))
    }

    @Test fun portsAreSimplified() {
        assertEquals(
            listOf("8080 -> 80/tcp"),
            PortFormat.simplify("0.0.0.0:8080->80/tcp, :::8080->80/tcp"),
        )
        assertEquals(listOf("53 -> 53/udp", "443 -> 443/tcp"), PortFormat.simplify(listOf("0.0.0.0:53->53/udp", "0.0.0.0:443->443/tcp")))
        assertEquals(emptyList<String>(), PortFormat.simplify("80/tcp"))
        assertEquals(emptyList<String>(), PortFormat.simplify(""))
    }

    private fun container(id: String, service: String, state: String, ports: String = "") =
        ContainerInstance(id, "$service-1", service, state, "", "img:1", ports, "")

    @Test fun containersJoinTheirServiceStartTime() {
        val detail = StackDetail(
            services = listOf(ServiceStatus("web", "running", listOf("0.0.0.0:8080->80/tcp"), ago(100))),
            containers = listOf(container("a".repeat(12), "web", "running", "ignored")),
            lastUpdated = null, lastStartedAt = null,
        )
        val view = detail.containerViews().single()
        assertEquals(ago(100), view.startedAt)
        assertEquals(listOf("8080 -> 80/tcp"), view.ports)
    }

    @Test fun stoppedContainerHasNoUptimeAndUsesItsOwnPortsWhenTheServiceHasNone() {
        val detail = StackDetail(
            services = listOf(ServiceStatus("db", "exited", emptyList(), ago(500))),
            containers = listOf(container("b".repeat(12), "db", "exited", "0.0.0.0:5432->5432/tcp")),
            lastUpdated = null, lastStartedAt = null,
        )
        val view = detail.containerViews().single()
        assertNull(view.startedAt)
        assertEquals(listOf("5432 -> 5432/tcp"), view.ports)
    }

    @Test fun containerWithoutServiceEntryUsesItsOwnPorts() {
        val detail = StackDetail(
            services = emptyList(),
            containers = listOf(container("c".repeat(12), "cache", "running", "0.0.0.0:6379->6379/tcp")),
            lastUpdated = null, lastStartedAt = null,
        )
        assertEquals(listOf("6379 -> 6379/tcp"), detail.containerViews().single().ports)
    }

    @Test fun serviceWithoutContainerStillShows() {
        val detail = StackDetail(
            services = listOf(ServiceStatus("lonely", "running", emptyList(), ago(10))),
            containers = emptyList(), lastUpdated = null, lastStartedAt = null,
        )
        assertEquals("lonely", detail.containerViews().single().service)
    }
}
