package com.feerlessleadr.dockge.ui.stacks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.feerlessleadr.dockge.api.StackStatus

fun StackStatus.label(): String = when (this) {
    StackStatus.Running -> "Running"
    StackStatus.Paused -> "Paused"
    StackStatus.Exited -> "Exited"
    StackStatus.Created -> "Created"
    StackStatus.Draft -> "Not deployed"
    StackStatus.Unknown -> "Not running"
}

@Composable
fun StackStatus.color(): Color = when (this) {
    StackStatus.Running -> Color(0xFF2E7D32)
    StackStatus.Paused -> Color(0xFFEF6C00)
    StackStatus.Exited -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Color for a container state or health value as the server reports it. */
@Composable
fun containerColor(state: String, health: String): Color = when {
    health == "unhealthy" -> MaterialTheme.colorScheme.error
    health == "starting" -> Color(0xFFEF6C00)
    state == "running" -> Color(0xFF2E7D32)
    state == "paused" || state == "restarting" -> Color(0xFFEF6C00)
    state == "exited" || state == "dead" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun containerLabel(state: String, health: String): String =
    if (health.isNotBlank() && state == "running") "running ($health)" else state.ifBlank { "unknown" }
