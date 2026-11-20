package com.feerlessleadr.dockge.ui.instances

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.AppIcon
import com.feerlessleadr.dockge.BuildConfig
import com.feerlessleadr.dockge.R
import com.feerlessleadr.dockge.ui.components.AppNavigationBar
import com.feerlessleadr.dockge.ui.components.SectionHeader
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.feerlessleadr.dockge.ui.components.Tabs
import com.feerlessleadr.dockge.ui.components.ScreenHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: InstancesViewModel, onNavigate: (String) -> Unit, onOpenAlerts: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppIcon.current(context)) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        bottomBar = { AppNavigationBar(Tabs.SETTINGS, onNavigate) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenHeader("Settings")
            SectionHeader("Notifications")
            Card(
                onClick = onOpenAlerts, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Alerts", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (vm.alerts.settings().enabled) "On. Updates, crash loops, unhealthy containers, backups, security." else "Off. Tap to choose what to be told about.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
            SectionHeader("Appearance")
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use wallpaper colors", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (com.feerlessleadr.dockge.ui.theme.ThemePrefs.supported) "Follow your Android theme instead of the app blue." else "Needs Android 12 or newer.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = com.feerlessleadr.dockge.ui.theme.ThemePrefs.dynamic,
                        enabled = com.feerlessleadr.dockge.ui.theme.ThemePrefs.supported,
                        onCheckedChange = { com.feerlessleadr.dockge.ui.theme.ThemePrefs.set(context, it) },
                    )
                }
            }
            SectionHeader("App icon")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                AppIcon.entries.forEach { icon ->
                    IconChoice(
                        icon = icon,
                        selected = selected == icon,
                        modifier = Modifier.weight(1f),
                    ) {
                        AppIcon.select(context, icon)
                        selected = icon
                    }
                }
            }
            Text(
                "Your launcher may take a few seconds to show the new icon. A shortcut you placed on the home screen can " +
                    "disappear when you switch; add it again from the app drawer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("About")
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Dockge for Android", style = MaterialTheme.typography.titleMedium)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    Text("Build ${BuildConfig.GIT_SHA}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun IconChoice(icon: AppIcon, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        onClick = onSelect,
        modifier = modifier.then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val (bg, fg) = when (icon) {
                AppIcon.Blue -> Color(0xFF1D63ED) to R.drawable.ic_launcher_foreground
                AppIcon.Light -> Color(0xFFDCE6FF) to R.drawable.ic_launcher_fg_dark
            }
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(bg)) {
                androidx.compose.foundation.Image(
                    painterResource(fg), contentDescription = null,
                    modifier = Modifier.fillMaxSize().scale(1.4f), contentScale = ContentScale.Fit,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected) Icon(rememberVectorPainter(Icons.Filled.CheckCircle), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(icon.title, style = MaterialTheme.typography.titleSmall)
            }
            Text(icon.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
