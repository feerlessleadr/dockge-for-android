package com.feerlessleadr.dockge.ui.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalUriHandler
import com.feerlessleadr.dockge.api.ImageLinks
import com.feerlessleadr.dockge.api.ImageUpdate
import com.feerlessleadr.dockge.api.ServerUpdates
import com.feerlessleadr.dockge.data.Action
import com.feerlessleadr.dockge.data.ActionOutcome
import com.feerlessleadr.dockge.data.ContainerAction
import com.feerlessleadr.dockge.data.ConnectionState
import com.feerlessleadr.dockge.data.StackAction
import com.feerlessleadr.dockge.ui.instances.ActionKey
import com.feerlessleadr.dockge.ui.instances.ActionUi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.api.StackDetail
import com.feerlessleadr.dockge.core.Uptime
import com.feerlessleadr.dockge.core.containerViews
import com.feerlessleadr.dockge.ui.instances.InstancesViewModel
import kotlinx.coroutines.delay
import java.time.Instant

private const val REFRESH_MS = 10_000L

/** After a failed load, try again sooner. */
private const val RETRY_MS = 3_000L

private data class Loaded(val detail: StackDetail, val at: Instant)

/** Read-only view of one stack: containers with state, health, image, uptime and ports. Refreshes every 10 s. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StackDetailScreen(vm: InstancesViewModel, instanceId: String, endpoint: String, stackName: String, onBack: () -> Unit) {
    val servers by vm.stacks(instanceId).collectAsState(initial = null)
    val stack = servers?.firstOrNull { it.endpoint == endpoint }?.stacks?.firstOrNull { it.name == stackName }

    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var manualRefresh by remember { mutableIntStateOf(0) }

    val connection by vm.connection(instanceId).collectAsState(initial = ConnectionState.Connecting)
    val server = servers?.firstOrNull { it.endpoint == endpoint }
    val actionUi by vm.action.collectAsState()
    val mine = actionUi?.takeIf { it.key == ActionKey(instanceId, endpoint, stackName) }
    val anyRunning = actionUi?.state?.running == true
    // Writes only while connected, and not to a linked server that this instance reports as offline.
    val canAct = connection == ConnectionState.Connected && server?.status != "offline" && !anyRunning
    var confirming by remember { mutableStateOf<Pending?>(null) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    // Runs the action now, or asks first when it has a warning.
    fun request(a: Action, target: String, run: () -> Unit) {
        if (a.warning == null) { run(); showSheet = true } else confirming = Pending(a, target, run)
    }

    // When an action ends, reload the containers instead of waiting for the next 10 s refresh.
    LaunchedEffect(mine?.state?.outcome) {
        if (mine?.state?.outcome != null) {
            manualRefresh++
            // The server drops its update entries for a stack it just updated; ask again so the badge matches.
            if (mine.action == StackAction.Update) vm.refreshUpdates(instanceId)
        }
    }

    val allUpdates by vm.updates(instanceId).collectAsState(initial = null)
    val serverUpdates = allUpdates?.get(endpoint)
    val available = serverUpdates?.availableFor(stackName).orEmpty()
    val uriHandler = LocalUriHandler.current

    // Keyed on the connection too: when the instance reconnects (for example after the phone slept) the loop starts
    // again at once instead of showing the old "Not connected" error until the next tick.
    LaunchedEffect(instanceId, endpoint, stackName, manualRefresh, connection) {
        while (true) {
            val result = vm.loadDetail(instanceId, endpoint, stackName)
            result
                .onSuccess { loaded = Loaded(it, Instant.now()); error = null }
                .onFailure { error = it.message ?: "Could not load this stack." }
            refreshing = false
            delay(if (result.isSuccess) REFRESH_MS else RETRY_MS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stack?.title ?: stackName) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; manualRefresh++ },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val data = loaded
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        stack?.let { Text(it.status.label(), color = it.status.color(), style = MaterialTheme.typography.titleMedium) }
                        error?.let { Text("$it Showing the last data.", color = MaterialTheme.colorScheme.error) }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            StackAction.entries.filter { it != StackAction.Update }.forEach { a ->
                                FilledTonalButton(
                                    enabled = canAct,
                                    onClick = { request(a, stack?.title ?: stackName) { vm.runAction(instanceId, endpoint, stackName, a) } },
                                ) { Text(a.label) }
                            }
                        }
                        if (!canAct && !anyRunning) {
                            Text(
                                if (connection != ConnectionState.Connected) "Actions are available when this instance is connected."
                                else "Actions are not available while this server is offline.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (anyRunning && mine == null) Text("Another action is running. Wait for it to finish.", style = MaterialTheme.typography.bodySmall)
                        if (mine != null && !showSheet) {
                            TextButton(onClick = { showSheet = true }) { Text(actionTitle(mine)) }
                        }
                    }
                }
                item {
                    UpdateSection(
                        updates = serverUpdates,
                        stackName = stackName,
                        available = available,
                        canAct = canAct,
                        openLink = { url -> runCatching { uriHandler.openUri(url) } },
                        onUpdate = { request(StackAction.Update, stack?.title ?: stackName) { vm.updateStack(instanceId, endpoint, stackName) } },
                    )
                }
                if (data == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(if (error == null) "Loading..." else "No data yet.")
                        }
                    }
                } else {
                    val views = data.detail.containerViews()
                    if (views.isEmpty()) {
                        item { Text("This stack has no containers.") }
                    }
                    items(views, key = { it.id }) { c ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(c.service.ifBlank { c.name }, style = MaterialTheme.typography.titleMedium)
                                Text(containerLabel(c.state, c.health), color = containerColor(c.state, c.health), style = MaterialTheme.typography.labelLarge)
                                if (c.name.isNotBlank() && c.name != c.service) Text(c.name, style = MaterialTheme.typography.bodySmall)
                                if (c.image.isNotBlank()) Text("Image: ${c.image}", style = MaterialTheme.typography.bodyMedium)
                                if (c.isRunning) Text("Uptime: ${Uptime.format(c.startedAt, data.at)}", style = MaterialTheme.typography.bodyMedium)
                                if (c.ports.isNotEmpty()) Text("Ports: ${c.ports.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                                val shown = if (c.isRunning) listOf(ContainerAction.Restart, ContainerAction.Stop) else listOf(ContainerAction.Start)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    shown.forEach { a ->
                                        TextButton(
                                            enabled = canAct,
                                            onClick = {
                                                val label = c.name.ifBlank { c.service }
                                                request(a, label) { vm.runContainerAction(instanceId, endpoint, stackName, c.id, label, a) }
                                            },
                                        ) { Text(a.label) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirming?.let { p ->
        val where = listOfNotNull(vm.get(instanceId)?.name, server?.name?.takeIf { endpoint.isNotEmpty() }).joinToString(" / ")
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("${p.action.label} ${p.target}?") },
            text = { Text("On $where.\n\n${p.action.warning}") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    p.run()
                    showSheet = true
                }) { Text(p.action.label) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }

    if (mine != null && showSheet) {
        val scroll = rememberScrollState()
        LaunchedEffect(mine.state.output) { scroll.animateScrollTo(scroll.maxValue) }
        ModalBottomSheet(
            onDismissRequest = { showSheet = false; vm.dismissAction() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(actionTitle(mine), style = MaterialTheme.typography.titleMedium)
                (mine.state.outcome as? ActionOutcome.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                if (mine.state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    mine.state.output.ifBlank { if (mine.state.running) "Waiting for output..." else "No output." },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(scroll),
                )
                TextButton(onClick = { showSheet = false; vm.dismissAction() }, modifier = Modifier.align(Alignment.End)) {
                    Text(if (mine.state.running) "Hide" else "Close")
                }
            }
        }
    }
}

/** What the server's image watcher knows about this stack's images, and the one-tap update (confirmed by the caller). */
@Composable
private fun UpdateSection(
    updates: ServerUpdates?,
    stackName: String,
    available: List<ImageUpdate>,
    canAct: Boolean,
    openLink: (String) -> Unit,
    onUpdate: () -> Unit,
) {
    when {
        updates == null -> Unit
        !updates.watcherEnabled ->
            Text("Update checks are off on this server. Turn on the image watcher in its Dockge settings.", style = MaterialTheme.typography.bodySmall)
        available.isEmpty() -> {
            val checked = updates.forStack(stackName).mapNotNull { it.lastChecked }.maxOrNull()
            Text(
                if (checked == null) "No update information for this stack yet." else "Images are up to date (checked ${Uptime.format(checked, Instant.now())} ago).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        else -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (available.size == 1) "Update available" else "${available.size} updates available", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                available.forEach { u ->
                    Column {
                        Text(u.image, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${ImageLinks.shortDigest(u.localDigest)} -> ${ImageLinks.shortDigest(u.remoteDigest)}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ImageLinks.registryUrl(u.image)?.let { url -> TextButton(onClick = { openLink(url) }) { Text("Image page") } }
                    }
                }
                Text("The server has no changelog. The image page is the best place to read what changed.", style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(enabled = canAct, onClick = onUpdate) { Text("Update stack") }
            }
        }
    }
}

private class Pending(val action: Action, val target: String, val run: () -> Unit)

private fun actionTitle(ui: ActionUi): String = when (ui.state.outcome) {
    null -> "${ui.action.doing} ${ui.target}..."
    ActionOutcome.Done -> "${ui.action.label} finished: ${ui.target}"
    ActionOutcome.RolloutPending -> "Rolling update started: ${ui.target}. It continues on the server."
    is ActionOutcome.Failed -> "${ui.action.label} failed: ${ui.target}"
}
