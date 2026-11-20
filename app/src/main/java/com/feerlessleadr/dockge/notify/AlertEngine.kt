package com.feerlessleadr.dockge.notify

import android.content.Context
import com.feerlessleadr.dockge.api.ServerUpdates
import com.feerlessleadr.dockge.core.Instance
import java.time.Instant

/** What the owner wants to hear about. Everything is on by default; [enabled] is the master switch. */
data class AlertSettings(
    val enabled: Boolean = false,
    val updates: Boolean = true,
    /** Crash loops and unhealthy containers. */
    val containers: Boolean = true,
    /** Failed backups. */
    val backup: Boolean = true,
    /** Critical or high findings of the security scan. */
    val security: Boolean = true,
)

interface AlertSettingsStore {
    fun load(): AlertSettings
    fun save(settings: AlertSettings)
}

/** Remembers which events were already handled, per instance, so a restart or a new poll never repeats an alert. */
interface SeenStore {
    /** Null when this instance was never watched before (the first look is then silent). */
    fun load(instanceId: String): List<String>?
    fun save(instanceId: String, keys: List<String>)
    fun remove(instanceId: String)
}

class PrefsAlertSettingsStore(context: Context) : AlertSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    override fun load() = AlertSettings(
        enabled = prefs.getBoolean("enabled", false),
        updates = prefs.getBoolean("updates", true),
        containers = prefs.getBoolean("containers", true),
        backup = prefs.getBoolean("backup", true),
        security = prefs.getBoolean("security", true),
    )

    override fun save(settings: AlertSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putBoolean("updates", settings.updates)
            .putBoolean("containers", settings.containers)
            .putBoolean("backup", settings.backup)
            .putBoolean("security", settings.security)
            .commit()
    }
}

class PrefsSeenStore(context: Context) : SeenStore {
    private val prefs = context.applicationContext.getSharedPreferences("alerts_seen", Context.MODE_PRIVATE)

    override fun load(instanceId: String): List<String>? =
        prefs.getString(instanceId, null)?.split('\n')?.filter { it.isNotEmpty() }

    override fun save(instanceId: String, keys: List<String>) {
        prefs.edit().putString(instanceId, keys.joinToString("\n")).apply()
    }

    override fun remove(instanceId: String) {
        prefs.edit().remove(instanceId).apply()
    }
}

/**
 * Turns what the servers report into alerts. Pure logic: no Android, no network. Each source (update status, the
 * monitoring overview) is looked at silently the first time per instance, because everything it lists then is old
 * news; after that only events with a new key raise an alert.
 */
class AlertEngine(
    private val seen: SeenStore,
    private val settings: () -> AlertSettings,
    private val emit: (Alert) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val memory = mutableMapOf<String, LinkedHashSet<String>>()

    private fun keys(instanceId: String): LinkedHashSet<String> =
        memory.getOrPut(instanceId) { LinkedHashSet(seen.load(instanceId).orEmpty()) }

    /** Marks [fresh] as seen and returns those that were new; the very first look at a source returns nothing. */
    private fun newOnes(instanceId: String, source: String, fresh: List<String>): List<String> {
        val keys = keys(instanceId)
        val marker = "baseline:$source"
        val first = marker !in keys
        val added = fresh.filter { it !in keys }
        keys.add(marker)
        keys.addAll(added)
        // Keep the newest keys. The "baseline" markers stay: losing one would make that source silent again.
        val it = keys.iterator()
        while (keys.size > MAX_KEYS && it.hasNext()) {
            if (!it.next().startsWith("baseline:")) it.remove()
        }
        seen.save(instanceId, keys.toList())
        return if (first) emptyList() else added
    }

    fun forget(instanceId: String) {
        memory.remove(instanceId)
        seen.remove(instanceId)
    }

    /** Image updates: one alert per stack that has a new update (a new image or a new remote digest). */
    fun onUpdates(instance: Instance, byServer: Map<String, ServerUpdates>) {
        val all = byServer.flatMap { (endpoint, server) ->
            server.images.filter { it.updateAvailable }.map { endpoint to it }
        }
        val fresh = newOnes(instance.id, "updates", all.map { (e, u) -> "update:$e:${u.stack}:${u.image}:${u.remoteDigest}" })
        if (fresh.isEmpty() || !settings().updates) return
        val freshSet = fresh.toSet()
        all.filter { (e, u) -> "update:$e:${u.stack}:${u.image}:${u.remoteDigest}" in freshSet }
            .groupBy { (e, u) -> e to u.stack }
            .forEach { (group, items) ->
                val (endpoint, stack) = group
                val images = items.joinToString(", ") { it.second.image }
                val where = if (endpoint.isEmpty()) "" else " on $endpoint"
                emit(
                    Alert(
                        key = "update:${instance.id}:$endpoint:$stack:" + items.joinToString(",") { it.second.remoteDigest },
                        kind = AlertKind.ImageUpdate,
                        title = "Update available: $stack",
                        text = "${instance.name}$where: $images",
                        target = AlertTarget(instance.id, endpoint, stack),
                        time = clock(),
                    ),
                )
            }
    }

    /** Crash loops, unhealthy containers, failed backups and security findings from the monitoring overview. */
    fun onOverview(instance: Instance, overview: MonitoringOverview, stacks: List<Pair<String, String>>) {
        val s = settings()

        val crashKeys = overview.crashes.map { "crash:${it.containerId}:${it.timestamp}" }
        val freshCrash = newOnes(instance.id, "crash", crashKeys).toSet()
        val healthKeys = overview.health.map { "health:${it.containerId}:${it.timestamp}" }
        val freshHealth = newOnes(instance.id, "health", healthKeys).toSet()
        val backupKeys = listOfNotNull(overview.backupTimestamp?.let { "backup:$it:${overview.backupSuccess}" })
        val freshBackup = newOnes(instance.id, "backup", backupKeys).toSet()
        val securityKeys = overview.security.map { "security:${it.image}:${it.severity}" }
        val freshSecurity = newOnes(instance.id, "security", securityKeys).toSet()

        if (s.containers) {
            overview.crashes.filter { "crash:${it.containerId}:${it.timestamp}" in freshCrash }.forEach {
                val match = stackFor(it.containerName, stacks)
                emit(
                    Alert(
                        key = "crash:${instance.id}:${it.containerId}:${it.timestamp}",
                        kind = AlertKind.CrashLoop,
                        title = "Crash loop: ${it.containerName}",
                        text = "${instance.name}: restarted ${it.restartCount} times in ${it.windowMinutes} min.",
                        target = AlertTarget(instance.id, match?.first ?: "", match?.second),
                        time = timeOf(it.timestamp),
                    ),
                )
            }
            overview.health.filter { "health:${it.containerId}:${it.timestamp}" in freshHealth }.forEach {
                val match = it.stackName?.let { name -> stacks.firstOrNull { (_, n) -> n == name } } ?: stackFor(it.containerName, stacks)
                val detail = it.message.trim().take(160).ifEmpty { "Health status: ${it.healthStatus}." }
                emit(
                    Alert(
                        key = "health:${instance.id}:${it.containerId}:${it.timestamp}",
                        kind = AlertKind.Unhealthy,
                        title = "Unhealthy: ${it.containerName}",
                        text = "${instance.name}: $detail",
                        target = AlertTarget(instance.id, match?.first ?: "", match?.second ?: it.stackName),
                        time = timeOf(it.timestamp),
                    ),
                )
            }
        }
        // Only failures: a successful backup every night would be noise. The newest attempt is what the server reports.
        if (s.backup && overview.backupSuccess == false && backupKeys.any { it in freshBackup }) {
            emit(
                Alert(
                    key = "backup:${instance.id}:${overview.backupTimestamp}",
                    kind = AlertKind.Backup,
                    title = "Backup failed",
                    text = "${instance.name}: the last Dockge backup did not succeed.",
                    target = AlertTarget(instance.id),
                    time = overview.backupTimestamp?.let(::timeOf) ?: clock(),
                ),
            )
        }
        if (s.security) {
            overview.security.filter { "security:${it.image}:${it.severity}" in freshSecurity }.forEach {
                val match = stacks.firstOrNull { (_, n) -> n == it.stack }
                emit(
                    Alert(
                        key = "security:${instance.id}:${it.image}:${it.severity}",
                        kind = AlertKind.Security,
                        title = "Security: ${it.severity.lowercase()} findings",
                        text = "${instance.name}: ${it.image}" + if (it.stack.isNotEmpty()) " (stack ${it.stack})" else "",
                        target = AlertTarget(instance.id, match?.first ?: "", match?.second),
                        time = clock(),
                    ),
                )
            }
        }
    }

    private fun timeOf(timestamp: String): Long = runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrDefault(clock())

    companion object {
        const val MAX_KEYS = 500

        /**
         * Compose names containers `<stack>-<service>-<n>` (or with `_`). The longest stack name that the container
         * name starts with, followed by - or _, is the stack; no match means the alert opens the instance only.
         */
        fun stackFor(containerName: String, stacks: List<Pair<String, String>>): Pair<String, String>? {
            val lower = containerName.lowercase()
            return stacks
                .filter { (_, name) -> name.isNotEmpty() && (lower.startsWith(name.lowercase() + "-") || lower.startsWith(name.lowercase() + "_")) }
                .maxByOrNull { it.second.length }
        }
    }
}
