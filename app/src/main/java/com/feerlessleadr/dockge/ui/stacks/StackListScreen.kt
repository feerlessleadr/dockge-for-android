package com.feerlessleadr.dockge.ui.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.api.Stack
import com.feerlessleadr.dockge.api.StackStatus
import com.feerlessleadr.dockge.data.ConnectionState
import com.feerlessleadr.dockge.data.UpdateAll
import com.feerlessleadr.dockge.ui.components.ListCard
import com.feerlessleadr.dockge.ui.components.ScreenHeader
import com.feerlessleadr.dockge.ui.components.StatusChip
import com.feerlessleadr.dockge.ui.components.StatusTile
import com.feerlessleadr.dockge.ui.components.Tone
import com.feerlessleadr.dockge.ui.instances.InstancesViewModel
import com.feerlessleadr.dockge.ui.instances.label

private sealed interface ListItem {
    data class Header(val key: String, val title: String, val offline: Boolean, val summary: String) : ListItem
    data class Row(val key: String, val stack: Stack) : ListItem
}

private enum class Filter(val label: String) { All("All"), Running("Running"), Updates("Updates"), Stopped("Stopped") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackListScreen(vm: InstancesViewModel, instanceId: String, onBack: () -> Unit, onOpen: (endpoint: String, name: String) -> Unit) {
    val instance = remember(instanceId) { vm.get(instanceId) }
    val servers by vm.stacks(instanceId).collectAsState(initial = null)
    val connection by vm.connection(instanceId).collectAsState(initial = ConnectionState.Connecting)
    var query by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    val updates by vm.updates(instanceId).collectAsState(initial = null)
    val self by vm.selfUpdate(instanceId).collectAsState(initial = null)
    val batch by vm.updateAll.collectAsState()
    var confirmingAll by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Opening the list asks for the update status again (it does nothing until the stack list is known).
    val listKnown = servers != null
    LaunchedEffect(instanceId, listKnown, connection == ConnectionState.Connected) {
        if (listKnown && connection == ConnectionState.Connected) vm.refreshUpdates(instanceId)
    }

    Scaffold(
    ) { padding ->
        val all = servers
        if (all == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                ScreenHeader(instance?.name ?: "Instance", onBack = onBack)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Waiting for the stack list...") }
            }
            return@Scaffold
        }
        val allStacks = all.flatMap { it.stacks }
        val running = allStacks.count { it.status == StackStatus.Running }
        val multi = all.size > 1
        fun hasUpdate(s: Stack) = updates?.get(s.endpoint)?.availableFor(s.name)?.isNotEmpty() == true
        val withUpdates = updates?.values?.sumOf { it.availableCount } ?: 0
        // Dockge's own stacks are never part of "update all" (see UpdateAll.plan).
        val updateTargets = UpdateAll.plan(all, updates.orEmpty(), self?.status?.repo)
        val watcherOff = all.filter { updates?.get(it.endpoint)?.watcherEnabled == false }
        val q = query.trim()

        val items = buildList {
            for (server in all) {
                val shown = server.stacks.filter { s ->
                    (q.isEmpty() || s.title.contains(q, true) || s.name.contains(q, true)) &&
                        when (filter) {
                            Filter.All -> true
                            Filter.Running -> s.status == StackStatus.Running
                            Filter.Updates -> hasUpdate(s)
                            Filter.Stopped -> s.status != StackStatus.Running
                        }
                }
                if (shown.isEmpty() && (q.isNotEmpty() || filter != Filter.All)) continue
                if (multi) {
                    val up = server.stacks.count { it.status == StackStatus.Running }
                    val upd = updates?.get(server.endpoint)?.availableCount ?: 0
                    add(ListItem.Header("h:${server.endpoint}", server.name, server.status == "offline", "${server.stacks.size} stacks, $up running" + if (upd > 0) ", $upd with updates" else ""))
                }
                shown.forEach { add(ListItem.Row("${server.endpoint}|${it.name}", it)) }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScreenHeader(
                        instance?.name ?: "Instance", onBack = onBack,
                        subtitle = "${allStacks.size} stacks, $running running" + if (multi) " on ${all.size} servers" else "",
                    )
                    if (connection != ConnectionState.Connected) {
                        Text(
                            "${connection.label()}. Showing the last list received.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    if (watcherOff.isNotEmpty()) {
                        Text(
                            "Update checks are off on ${watcherOff.joinToString { it.name }}. Turn on the image watcher in that Dockge's settings.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search stacks") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
                        } else null,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Filter.entries.forEachIndexed { i, f ->
                            SegmentedButton(
                                selected = filter == f,
                                onClick = { filter = f },
                                shape = SegmentedButtonDefaults.itemShape(i, Filter.entries.size),
                                label = { Text(if (f == Filter.Updates && withUpdates > 0) "Updates $withUpdates" else f.label, maxLines = 1) },
                            )
                        }
                    }
                    if (updateTargets.isNotEmpty()) {
                        UpdateAllButton(
                            count = updateTargets.size,
                            enabled = connection == ConnectionState.Connected && batch?.running != true,
                            onClick = { refusal = null; confirmingAll = true },
                        )
                    }
                    refusal?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (items.none { it is ListItem.Row }) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                allStacks.isEmpty() -> "This instance has no stacks."
                                q.isNotEmpty() -> "No stack matches the search."
                                else -> "No stacks in this view."
                            },
                        )
                    }
                }
            } else {
                items(items, key = { if (it is ListItem.Header) it.key else (it as ListItem.Row).key }) { item ->
                    when (item) {
                        is ListItem.Header -> Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (item.offline) "Offline. ${item.summary} (last known)" else item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is ListItem.Row -> StackCard(item.stack, hasUpdate = hasUpdate(item.stack)) { onOpen(item.stack.endpoint, item.stack.name) }
                    }
                }
            }
        }

        if (confirmingAll) {
            UpdateAllConfirm(
                serverName = instance?.name ?: "this instance",
                targets = updateTargets,
                onConfirm = {
                    confirmingAll = false
                    refusal = vm.startUpdateAll(instanceId)
                },
                onDismiss = { confirmingAll = false },
            )
        }
        batch?.let { state ->
            UpdateAllProgress(state, onClose = { vm.dismissUpdateAll() })
        }
    }
}

@Composable
private fun StackCard(stack: Stack, hasUpdate: Boolean, onClick: () -> Unit) {
    ListCard(onClick = onClick, enabled = stack.isManaged) {
        StatusTile(stack.status.icon(), stack.status.tone())
        Column(Modifier.weight(1f)) {
            Text(stack.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                if (stack.displayName.isNotBlank()) add(stack.name)
                if (!stack.isManaged) add("Not managed by Dockge") else if (stack.isExternal) add("External stack")
            }
            Text(
                (listOf(stack.status.label()) + sub).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasUpdate) StatusChip("Update", Tone.Warn)
    }
}
