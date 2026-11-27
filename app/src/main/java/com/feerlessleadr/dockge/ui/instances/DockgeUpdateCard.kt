package com.feerlessleadr.dockge.ui.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.R
import com.feerlessleadr.dockge.api.ImageLinks
import com.feerlessleadr.dockge.api.SelfUpdatePolicy
import com.feerlessleadr.dockge.data.SelfUpdateState
import com.feerlessleadr.dockge.ui.components.ListCard
import com.feerlessleadr.dockge.ui.components.StatusTile
import com.feerlessleadr.dockge.ui.components.Tone
import com.feerlessleadr.dockge.ui.components.container
import com.feerlessleadr.dockge.ui.components.content

/** Whether the Dockge update card has something to say (an update, a run in progress or its result). */
fun dockgeUpdateNeedsAttention(state: SelfUpdateState?): Boolean {
    if (state == null) return false
    val status = state.status ?: return state.watching
    return state.watching || SelfUpdatePolicy.isActive(status.operation) || SelfUpdatePolicy.targetImage(status) != null
}

/**
 * The safe Dockge self-update: offer, confirm, and then show the server's stages while Dockge restarts. The app never
 * runs the plain stack update on Dockge's own stack (that would stop Dockge mid-update), so this card replaces it.
 */
@Composable
fun DockgeUpdateCard(state: SelfUpdateState?, onUpdate: () -> Unit) {
    val status = state?.status
    val operation = status?.operation
    val active = state?.watching == true || SelfUpdatePolicy.isActive(operation)
    val target = status?.let { SelfUpdatePolicy.targetImage(it) }
    var confirming by remember { mutableStateOf(false) }

    val tone = when {
        active -> Tone.Info
        operation?.state == "rollback-failed" || operation?.state == "failed" -> Tone.Error
        operation?.state == "rolled-back" || target != null -> Tone.Warn
        else -> Tone.Ok
    }
    ListCard(container = tone.container()) {
        StatusTile(R.drawable.ic_update, tone, Modifier.align(Alignment.Top))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val content = tone.content()
            when {
                active -> ProgressBody(state, content)
                operation != null && SelfUpdatePolicy.isFinished(operation) -> FinishedBody(operation.state, operation.message, content)
                target != null && status != null -> {
                    Text("Dockge update available", style = MaterialTheme.typography.titleMedium, color = content)
                    Text(
                        "${ImageLinks.shortDigest(status.localDigest)} → ${ImageLinks.shortDigest(status.remoteDigest)}",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = content,
                    )
                    Text(
                        "Dockge backs up its data first, then restarts. The app is disconnected for a short time and reconnects by itself.",
                        style = MaterialTheme.typography.bodySmall, color = content,
                    )
                    if (state?.reachable != true) {
                        Text("Dockge is not reachable right now.", style = MaterialTheme.typography.bodySmall, color = content)
                    }
                    Button(enabled = state?.reachable == true, onClick = { confirming = true }) { Text("Update Dockge") }
                }
                status != null -> Text("Dockge is up to date.", style = MaterialTheme.typography.titleMedium, color = content)
                else -> Text(state?.error ?: "Dockge's update status is not known yet.", style = MaterialTheme.typography.bodySmall, color = content)
            }
            // A refusal or a failed start is shown here, also when the card is in another state.
            val error = state?.error
            if (!active && error != null && status != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Update Dockge?") },
            text = {
                Text(
                    "Dockge backs up its data, then replaces its own container with the new version. Dockge is offline for a short time, " +
                        "so the app disconnects and reconnects by itself. If the new version does not start healthy, the previous version is restored automatically.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onUpdate() }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProgressBody(state: SelfUpdateState?, color: Color) {
    val operation = state?.status?.operation
    Text(
        if (state?.watching == true && operation?.stage == null) "Starting the Dockge update" else "Updating Dockge",
        style = MaterialTheme.typography.titleMedium, color = color,
    )
    Text(
        SelfUpdatePolicy.stageLabel(operation?.stage ?: "preparing"),
        style = MaterialTheme.typography.bodyMedium, color = color,
    )
    operation?.stageHistory?.filter { !it.skipped }?.forEach { stage ->
        val done = stage.finishedAt != null
        Text(
            (if (done) "✓ " else "… ") + SelfUpdatePolicy.stageLabel(stage.stage),
            style = MaterialTheme.typography.bodySmall, color = color,
        )
    }
    if (operation?.message?.isNotBlank() == true) {
        Text(operation.message, style = MaterialTheme.typography.bodySmall, color = color)
    }
    Text(
        "Dockge may restart during this step. The app reconnects by itself; nothing to do.",
        style = MaterialTheme.typography.bodySmall, color = color,
    )
    if (state?.reachable != true) {
        Text("Waiting for Dockge to answer...", style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun FinishedBody(state: String, message: String, color: Color) {
    val headline = when (state) {
        "succeeded" -> "Dockge was updated."
        "rolled-back" -> "The update failed. The previous Dockge version was restored."
        "rollback-failed" -> "The update failed and the rollback did not finish. Check Dockge and its Docker host now."
        else -> "The Dockge update did not complete."
    }
    Text(headline, style = MaterialTheme.typography.titleMedium, color = color)
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = color)
}
