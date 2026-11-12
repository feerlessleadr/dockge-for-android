package com.feerlessleadr.dockge.ui.instances

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.feerlessleadr.dockge.notify.NtfyState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(vm: InstancesViewModel, onBack: () -> Unit) {
    val ntfy = vm.ntfy
    val saved = remember { ntfy.settings() }
    var server by remember { mutableStateOf(saved.serverUrl) }
    var topic by remember { mutableStateOf(saved.topic) }
    var enabled by remember { mutableStateOf(saved.enabled) }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(ntfy.hasToken()) }
    var message by remember { mutableStateOf<String?>(null) }
    val state by ntfy.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val needsPermission = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    var permissionMissing by remember { mutableStateOf(needsPermission) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionMissing = !granted
    }

    fun save(clearToken: Boolean = false) {
        ntfy.save(server, topic, enabled, token, clearToken)
            .onSuccess {
                message = "Saved."
                if (clearToken) hasToken = false else if (token.isNotBlank()) hasToken = true
                token = ""
            }
            .onFailure { message = it.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alerts") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Alerts come from your own ntfy server. Dockge sends them through an Apprise server with an ntfy:// " +
                    "address (see the README). The app keeps one connection to the topic open; there is no Google push.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                server, { server = it }, label = { Text("ntfy server (https)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("For example https://ntfy.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                topic, { topic = it }, label = { Text("Topic") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Letters, digits, - and _. A long random name keeps it private on an open server.") },
            )
            OutlinedTextField(
                token, { token = it },
                label = { Text(if (hasToken) "Access token (saved, leave empty to keep)" else "Access token (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("Only needed when the topic requires login. Stored encrypted in the Android Keystore.") },
            )
            if (hasToken) TextButton(onClick = { save(clearToken = true) }) { Text("Remove saved token") }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Receive alerts", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    if (it && permissionMissing) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
            }
            if (enabled && permissionMissing) {
                Text(
                    "Android has not allowed notifications for this app yet, so alerts would stay invisible.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
            }

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Text(
                "Connection: " + when (val s = state) {
                    NtfyState.Off -> "off"
                    NtfyState.Connecting -> "connecting"
                    is NtfyState.Connected -> "listening"
                    is NtfyState.Failed -> "not connected. ${s.message}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedButton(
                onClick = {
                    scope.launch {
                        message = "Sending the test message."
                        message = ntfy.sendTest().fold(
                            { "Test message sent. It should arrive as an alert within seconds." },
                            { "Test failed: ${it.message}" },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send test message") }

            Text("Battery", style = MaterialTheme.typography.titleMedium)
            Text(
                "Android may stop background apps to save power. If alerts arrive late or stop, set this app to " +
                    "Unrestricted battery use.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open battery settings") }
        }
    }
}
