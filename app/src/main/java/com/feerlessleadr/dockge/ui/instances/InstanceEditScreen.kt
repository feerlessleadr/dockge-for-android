package com.feerlessleadr.dockge.ui.instances

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.core.InstanceColors

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun InstanceEditScreen(vm: InstancesViewModel, id: String?, onDone: () -> Unit) {
    val existing = remember(id) { id?.let(vm::get) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var color by remember { mutableStateOf(existing?.color ?: InstanceColors.palette.first()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add instance" else "Edit instance") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                address, { address = it }, label = { Text("Address (https)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("For example https://dockge.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text("Color", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InstanceColors.palette.forEach { c ->
                    val selected = c == color
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                            .border(BorderStroke(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface), CircleShape)
                            .clickable { color = c },
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (existing != null) {
                Text(
                    "Changing the address signs you out of this instance, so a saved sign-in is never sent to a different server.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    val result = if (existing == null) vm.add(name, address, color) else vm.update(existing.id, name, address, color)
                    result.onSuccess { onDone() }.onFailure { error = it.message }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            if (existing != null) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete instance") }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("The instance and its saved sign-in are removed from this phone. The server is not changed.") },
            confirmButton = { TextButton(onClick = { vm.delete(existing.id); confirmDelete = false; onDone() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
