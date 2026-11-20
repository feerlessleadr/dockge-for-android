package com.feerlessleadr.dockge.ui.stacks

import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.feerlessleadr.dockge.R
import com.feerlessleadr.dockge.api.StackStatus
import com.feerlessleadr.dockge.ui.components.Tone
import com.feerlessleadr.dockge.ui.theme.DockgeColors

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
    StackStatus.Running -> DockgeColors.status.ok
    StackStatus.Paused -> DockgeColors.status.warn
    StackStatus.Exited -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun StackStatus.tone(): Tone = when (this) {
    StackStatus.Running -> Tone.Ok
    StackStatus.Paused -> Tone.Warn
    StackStatus.Exited -> Tone.Error
    else -> Tone.Neutral
}

@DrawableRes
fun StackStatus.icon(): Int = when (this) {
    StackStatus.Running -> R.drawable.ic_play
    StackStatus.Paused -> R.drawable.ic_pause
    else -> R.drawable.ic_stop
}

/** Color for a container state or health value as the server reports it. */
@Composable
fun containerColor(state: String, health: String): Color = when (containerTone(state, health)) {
    Tone.Ok -> DockgeColors.status.ok
    Tone.Warn -> DockgeColors.status.warn
    Tone.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun containerTone(state: String, health: String): Tone = when {
    health == "unhealthy" -> Tone.Error
    health == "starting" -> Tone.Warn
    state == "running" -> Tone.Ok
    state == "paused" || state == "restarting" -> Tone.Warn
    state == "exited" || state == "dead" -> Tone.Error
    else -> Tone.Neutral
}

fun containerLabel(state: String, health: String): String =
    if (health.isNotBlank() && state == "running") "running ($health)" else state.ifBlank { "unknown" }
