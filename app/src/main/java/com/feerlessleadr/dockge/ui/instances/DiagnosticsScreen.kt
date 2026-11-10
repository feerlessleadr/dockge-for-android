package com.feerlessleadr.dockge.ui.instances

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Debug builds only: what this instance's connection did and the shape of the lists it pushed (endpoint names,
 * counts, a few stack names). Memory only; nothing is saved or sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: InstancesViewModel, instanceId: String, onBack: () -> Unit) {
    val log = remember(instanceId) { vm.log(instanceId) }
    val lines by (log?.lines ?: MutableStateFlow(emptyList())).collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics: ${vm.get(instanceId)?.name ?: ""}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Text("Build ${BuildConfig.GIT_SHA}, newest first", style = MaterialTheme.typography.labelLarge)
            LazyColumn {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
