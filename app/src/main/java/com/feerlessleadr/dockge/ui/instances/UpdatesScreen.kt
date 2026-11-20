package com.feerlessleadr.dockge.ui.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.api.Stack
import com.feerlessleadr.dockge.ui.components.ListCard
import com.feerlessleadr.dockge.ui.components.ScreenHeader
import com.feerlessleadr.dockge.ui.components.SectionHeader
import com.feerlessleadr.dockge.ui.components.StatusTile
import com.feerlessleadr.dockge.ui.components.Tone
import com.feerlessleadr.dockge.R

/** Every stack with an image update, from all saved instances, grouped by instance. Tap opens the stack. */
@Composable
fun UpdatesScreen(vm: InstancesViewModel, onBack: () -> Unit, onOpen: (instanceId: String, endpoint: String, name: String) -> Unit) {
    val rows by vm.rows.collectAsState()
    val total = rows.sumOf { it.updateCount ?: 0 }
    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ScreenHeader(
                    "Updates", onBack = onBack,
                    subtitle = when (total) { 0 -> "Everything is up to date"; 1 -> "1 stack has an update"; else -> "$total stacks have updates" },
                )
            }
            rows.filter { (it.updateCount ?: 0) > 0 }.forEach { row ->
                instanceUpdates(vm, row.instance.id, row.instance.name, onOpen)
            }
            if (total == 0) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text("No updates found.") }
            }
        }
    }
}

private fun LazyListScope.instanceUpdates(
    vm: InstancesViewModel, instanceId: String, instanceName: String,
    onOpen: (String, String, String) -> Unit,
) {
    item(key = "i:$instanceId") {
        val servers by vm.stacks(instanceId).collectAsState(initial = null)
        val updates by vm.updates(instanceId).collectAsState(initial = null)
        val withUpdates = servers.orEmpty().flatMap { it.stacks }.filter { s ->
            updates?.get(s.endpoint)?.availableFor(s.name)?.isNotEmpty() == true
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(instanceName)
            withUpdates.forEach { s -> UpdateRow(s, updates?.get(s.endpoint)?.availableFor(s.name).orEmpty().map { it.image }) { onOpen(instanceId, s.endpoint, s.name) } }
        }
    }
}

@Composable
private fun UpdateRow(stack: Stack, images: List<String>, onClick: () -> Unit) {
    ListCard(onClick = onClick, enabled = stack.isManaged) {
        StatusTile(R.drawable.ic_update, Tone.Warn)
        Column(Modifier.weight(1f)) {
            Text(stack.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                images.joinToString(", ").ifBlank { "Image update" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
