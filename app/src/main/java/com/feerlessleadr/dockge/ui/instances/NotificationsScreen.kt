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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.feerlessleadr.dockge.ui.components.AppNavigationBar
import com.feerlessleadr.dockge.ui.components.SectionHeader
import com.feerlessleadr.dockge.ui.components.Tabs
import com.feerlessleadr.dockge.ui.components.ScreenHeader
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.feerlessleadr.dockge.data.ConnectionState
import com.feerlessleadr.dockge.notify.AlertSettings
import com.feerlessleadr.dockge.notify.PollStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(vm: InstancesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = vm.alerts
    var settings by remember { mutableStateOf(controller.settings()) }
    val rows by vm.rows.collectAsState()
    val status by controller.watcher.status.collectAsState()

    val needsPermission = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    var permissionMissing by remember { mutableStateOf(needsPermission) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionMissing = !granted
    }

    fun change(new: AlertSettings) {
        settings = new
        controller.save(new)
        if (new.enabled && permissionMissing) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader("Alerts", onBack = onBack)
            Text(
                "The app watches your Dockge servers itself and notifies you. No notification server and no Google " +
                    "push are involved. While alerts are on, the app keeps its connections open in the background " +
                    "(Android shows a small permanent notification for that).",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (settings.enabled && permissionMissing) {
                Text(
                    "Android has not allowed notifications for this app yet, so alerts would stay invisible.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
            }

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow("Alerts", "Watch all saved instances in the background", settings.enabled) { change(settings.copy(enabled = it)) }
                    ToggleRow("Image updates", "A new update was found", settings.updates) { change(settings.copy(updates = it)) }
                    ToggleRow("Containers", "Crash loops and unhealthy containers", settings.containers) { change(settings.copy(containers = it)) }
                    ToggleRow("Backups", "A Dockge backup failed", settings.backup) { change(settings.copy(backup = it)) }
                    ToggleRow("Security", "Critical or high findings of the security scan", settings.security) { change(settings.copy(security = it)) }
                }
            }

            SectionHeader("Instances")
            if (rows.isEmpty()) Text("No instances yet.", style = MaterialTheme.typography.bodyMedium)
            rows.forEach { row ->
                val feed = when (val s = status[row.instance.id]) {
                    null, PollStatus.Waiting -> if (row.state == ConnectionState.Connected) "Waiting for the first check." else "Not connected."
                    is PollStatus.Ok -> "Monitoring feed read ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(s.at)}."
                    is PollStatus.Failed -> "Monitoring feed: ${s.message}"
                }
                Column {
                    Text(row.instance.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.enabled) feed else "Alerts are off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                "Image updates need the image watcher switched on in that Dockge. Crash, health, backup and security " +
                    "alerts need your Dockge user to be an administrator. Alerts about cleanup, linked-server problems " +
                    "and Dockge's own updates cannot be watched this way. Only the instance you added is read, not the " +
                    "servers linked to it.",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionHeader("Battery")
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

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
