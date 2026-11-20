package com.feerlessleadr.dockge.ui.instances

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.data.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceListScreen(
    vm: InstancesViewModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDiagnostics: (String) -> Unit,
    onNotifications: () -> Unit,
) {
    val rows by vm.rows.collectAsState()
    var loginFor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Instances")
                        Text("Build ${com.feerlessleadr.dockge.BuildConfig.GIT_SHA}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { TextButton(onClick = onNotifications) { Text("Alerts") } },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd) { Text("Add instance") } },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No instances yet. Add your first Dockge server.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.instance.id }) { row ->
                    InstanceCard(
                        row = row,
                        onClick = {
                            if (row.state is ConnectionState.NeedsLogin || row.state is ConnectionState.NeedsTwoFactor) {
                                loginFor = row.instance.id
                            } else if (row.state == ConnectionState.Connected) {
                                onOpen(row.instance.id)
                            }
                        },
                        onEdit = { onEdit(row.instance.id) },
                        onRetry = { vm.reconnect(row.instance.id) },
                        onSignOut = { vm.signOut(row.instance.id) },
                        onDiagnostics = { onDiagnostics(row.instance.id) },
                    )
                }
            }
        }
    }

    val target = rows.firstOrNull { it.instance.id == loginFor }
    if (target != null) {
        LoginDialog(target, vm, onDismiss = { loginFor = null })
    }
}

@Composable
private fun InstanceCard(row: InstanceRow, onClick: () -> Unit, onEdit: () -> Unit, onRetry: () -> Unit, onSignOut: () -> Unit, onDiagnostics: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(row.instance.color)))
            Column(Modifier.weight(1f)) {
                Text(row.instance.name, style = MaterialTheme.typography.titleMedium)
                Text(row.instance.baseUrl.substringAfter("://"), style = MaterialTheme.typography.bodySmall)
                val s = row.state
                Text(
                    if (s is ConnectionState.Failed) "${s.label()}: ${s.message}" else s.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor(s),
                )
                if (s == ConnectionState.Connected && row.stackCount != null) {
                    val servers = if (row.serverCount > 1) " on ${row.serverCount} servers" else ""
                    Text("${row.stackCount} stacks$servers, ${row.runningCount} running", style = MaterialTheme.typography.bodySmall)
                    row.updateCount?.takeIf { it > 0 }?.let {
                        Text(
                            if (it == 1) "1 stack has an update" else "$it stacks have updates",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Retry connection") }, onClick = { menu = false; onRetry() })
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; onSignOut() })
                    if (com.feerlessleadr.dockge.BuildConfig.DEBUG) {
                        DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { menu = false; onDiagnostics() })
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(s: ConnectionState): Color = when (s) {
    ConnectionState.Connected -> Color(0xFF2E7D32)
    is ConnectionState.Failed -> MaterialTheme.colorScheme.error
    is ConnectionState.NeedsLogin, is ConnectionState.NeedsTwoFactor -> Color(0xFFEF6C00)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun LoginDialog(row: InstanceRow, vm: InstancesViewModel, onDismiss: () -> Unit) {
    val id = row.instance.id
    // Plain remember on purpose: secrets must never be written to saved instance state.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val state = row.state

    LaunchedEffect(state) { if (state == ConnectionState.Connected) onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to ${row.instance.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is ConnectionState.NeedsLogin -> {
                        state.lastFailure?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                        OutlinedTextField(
                            password, { password = it }, label = { Text("Password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                    is ConnectionState.NeedsTwoFactor -> {
                        state.lastFailure?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(
                            code, { code = it.take(20) }, label = { Text("6-digit code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    ConnectionState.LoggingIn -> Text("Signing in...")
                    else -> Text(state.label())
                }
            }
        },
        confirmButton = {
            when (state) {
                is ConnectionState.NeedsLogin -> Button(
                    onClick = { vm.submitCredentials(id, username.trim(), password); password = "" },
                    enabled = username.isNotBlank() && password.isNotEmpty(),
                ) { Text("Sign in") }
                is ConnectionState.NeedsTwoFactor -> Button(
                    onClick = { vm.submitCode(id, code.trim()); code = "" },
                    enabled = code.isNotBlank(),
                ) { Text("Verify") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
