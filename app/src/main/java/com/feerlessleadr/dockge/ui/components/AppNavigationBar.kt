package com.feerlessleadr.dockge.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.feerlessleadr.dockge.R

/** Top-level routes shown in the bottom bar (ALERTS is opened from Settings). */
object Tabs {
    const val INSTANCES = "list"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"
    val all = listOf(INSTANCES, SETTINGS)
}

@Composable
fun AppNavigationBar(current: String, onNavigate: (String) -> Unit) {
    NavigationBar {
        Item(current, Tabs.INSTANCES, "Instances", painterResource(R.drawable.ic_server), onNavigate)
        Item(current, Tabs.SETTINGS, "Settings", rememberVectorPainter(Icons.Filled.Settings), onNavigate)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Item(
    current: String, route: String, label: String, icon: Painter, onNavigate: (String) -> Unit,
) {
    NavigationBarItem(
        selected = current == route,
        onClick = { if (current != route) onNavigate(route) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}
