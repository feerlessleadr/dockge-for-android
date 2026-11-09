package com.feerlessleadr.dockge.ui.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.api.StackStatus
import com.feerlessleadr.dockge.data.ConnectionState
import com.feerlessleadr.dockge.ui.instances.InstancesViewModel
import com.feerlessleadr.dockge.ui.instances.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackListScreen(vm: InstancesViewModel, instanceId: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val instance = remember(instanceId) { vm.get(instanceId) }
    val stacks by vm.stacks(instanceId).collectAsState(initial = null)
    val connection by vm.connection(instanceId).collectAsState(initial = ConnectionState.Connecting)
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(instance?.name ?: "Instance") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (connection != ConnectionState.Connected) {
                Text(
                    "${connection.label()}. Showing the last list received.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            val all = stacks
            if (all == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Waiting for the stack list...") }
                return@Column
            }
            val running = all.count { it.status == StackStatus.Running }
            Text(
                "${all.size} stacks, $running running",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val shown = all.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.name.contains(query.trim(), ignoreCase = true) }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (all.isEmpty()) "This instance has no stacks." else "No stack matches the search.")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.name }) { stack ->
                        Card(Modifier.fillMaxWidth().clickable(enabled = stack.isManaged) { onOpen(stack.name) }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stack.title, style = MaterialTheme.typography.titleMedium)
                                    if (stack.displayName.isNotBlank()) Text(stack.name, style = MaterialTheme.typography.bodySmall)
                                    if (!stack.isManaged) {
                                        Text("Not managed by Dockge", style = MaterialTheme.typography.bodySmall)
                                    } else if (stack.isExternal) {
                                        Text("External stack", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text(stack.status.label(), color = stack.status.color(), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
