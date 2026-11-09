package com.feerlessleadr.dockge.ui.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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

private data class Loaded(val detail: StackDetail, val at: Instant)

/** Read-only view of one stack: containers with state, health, image, uptime and ports. Refreshes every 10 s. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackDetailScreen(vm: InstancesViewModel, instanceId: String, stackName: String, onBack: () -> Unit) {
    val stacks by vm.stacks(instanceId).collectAsState(initial = null)
    val stack = stacks?.firstOrNull { it.name == stackName }

    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var manualRefresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(instanceId, stackName, manualRefresh) {
        while (true) {
            vm.loadDetail(instanceId, stackName)
                .onSuccess { loaded = Loaded(it, Instant.now()); error = null }
                .onFailure { error = it.message ?: "Could not load this stack." }
            refreshing = false
            delay(REFRESH_MS)
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
                            }
                        }
                    }
                }
            }
        }
    }
}
