package com.feerlessleadr.dockge.ui.stacks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.data.ActionOutcome
import com.feerlessleadr.dockge.data.UpdateAllState
import com.feerlessleadr.dockge.data.UpdateTarget

/** The "Update all" button of a server's stack list. Shown only when the server has stacks to update. */
@Composable
fun UpdateAllButton(count: Int, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("Update all ($count)")
    }
}

/** Asks before a run. Says what is left out, so the owner is never surprised by Dockge itself. */
@Composable
fun UpdateAllConfirm(serverName: String, targets: List<UpdateTarget>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update ${targets.size} stack${if (targets.size == 1) "" else "s"}?") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("On $serverName, each stack is pulled and recreated, one after another. Each stack is briefly down.")
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    targets.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                }
                Text(
                    "Dockge itself is not included. Its update is the safe Dockge update, offered on its own card.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Update all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Live progress of a run: the stack being updated, then every finished stack with its result. */
@Composable
fun UpdateAllProgress(state: UpdateAllState, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onClose() },
        title = {
            Text(
                when {
                    state.running -> "Updating ${state.done + 1} of ${state.total}"
                    state.failed == 0 && state.stoppedBecause == null -> "Update all finished"
                    else -> "Update all finished with problems"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                state.current?.let { Text("Now: ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                state.results.forEach { r ->
                    val mark = if (r.outcome is ActionOutcome.Failed) "✗" else "✓"
                    Row {
                        Text("$mark ${r.target.title}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (r.outcome is ActionOutcome.Failed) {
                        Text(r.outcome.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (state.running) Spacer(Modifier.height(4.dp))
                state.stoppedBecause?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, enabled = !state.running) { Text(if (state.running) "Running..." else "Close") }
        },
    )
}
