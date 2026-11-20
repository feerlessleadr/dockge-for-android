package com.feerlessleadr.dockge.ui.instances

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.R
import com.feerlessleadr.dockge.data.ConnectionState
import com.feerlessleadr.dockge.ui.components.AppNavigationBar
import com.feerlessleadr.dockge.ui.components.ListCard
import com.feerlessleadr.dockge.ui.components.StatusChip
import com.feerlessleadr.dockge.ui.components.StatusTile
import com.feerlessleadr.dockge.ui.components.Tabs
import com.feerlessleadr.dockge.ui.components.Tone
import com.feerlessleadr.dockge.ui.components.container
import com.feerlessleadr.dockge.ui.components.content
import androidx.compose.ui.graphics.vector.rememberVectorPainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceListScreen(
    vm: InstancesViewModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDiagnostics: (String) -> Unit,
    onNavigate: (String) -> Unit,
) {
    val rows by vm.rows.collectAsState()
    var loginFor by remember { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val connected = rows.count { it.state == ConnectionState.Connected }
    val withUpdates = rows.filter { (it.updateCount ?: 0) > 0 }
    val totalUpdates = withUpdates.sumOf { it.updateCount ?: 0 }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Instances") }, scrollBehavior = scroll) },
        bottomBar = { AppNavigationBar(Tabs.INSTANCES, onNavigate) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add instance") },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No instances yet. Add your first Dockge server.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "${rows.size} ${if (rows.size == 1) "server" else "servers"}, $connected connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                if (totalUpdates > 0) {
                    item {
                        ListCard(
                            onClick = { withUpdates.firstOrNull { it.state == ConnectionState.Connected }?.let { onOpen(it.instance.id) } },
                            container = Tone.Warn.container(),
                        ) {
                            Icon(painterResource(R.drawable.ic_update), contentDescription = null, tint = Tone.Warn.content(), modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (totalUpdates == 1) "1 stack has an update" else "$totalUpdates stacks have updates",
                                    style = MaterialTheme.typography.titleSmall, color = Tone.Warn.content(),
                                )
                                Text(
                                    if (withUpdates.size == 1) "On ${withUpdates[0].instance.name}" else "Across ${withUpdates.size} instances",
                                    style = MaterialTheme.typography.bodySmall, color = Tone.Warn.content(),
                                )
                            }
                        }
                    }
                }
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
    val s = row.state
    val tone = tone(s)
    ListCard(onClick = onClick) {
        when (tone) {
            Tone.Error -> StatusTile(rememberVectorPainter(Icons.Filled.Warning), tone)
            Tone.Warn -> StatusTile(rememberVectorPainter(Icons.Filled.Lock), tone)
            else -> StatusTile(R.drawable.ic_server, tone)
        }
        Column(Modifier.weight(1f)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(row.instance.color)))
                Text(
                    row.instance.name,
                    Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                row.instance.baseUrl.substringAfter("://"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (s is ConnectionState.Failed) "${s.label()}: ${s.message}" else statusLine(row),
                style = MaterialTheme.typography.labelMedium,
                color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else if (tone == Tone.Error) MaterialTheme.colorScheme.error else tone.content(),
            )
        }
        row.updateCount?.takeIf { it > 0 && s == ConnectionState.Connected }?.let {
            StatusChip(if (it == 1) "1 update" else "$it updates", Tone.Warn)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
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

private fun tone(s: ConnectionState): Tone = when (s) {
    ConnectionState.Connected -> Tone.Ok
    is ConnectionState.Failed -> Tone.Error
    is ConnectionState.NeedsLogin, is ConnectionState.NeedsTwoFactor -> Tone.Warn
    else -> Tone.Neutral
}

private fun statusLine(row: InstanceRow): String {
    val s = row.state
    if (s != ConnectionState.Connected || row.stackCount == null) return s.label()
    val servers = if (row.serverCount > 1) " on ${row.serverCount} servers" else ""
    return "Connected · ${row.stackCount} stacks$servers, ${row.runningCount} running"
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
