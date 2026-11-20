package com.feerlessleadr.dockge.ui.stacks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.core.ComposeProgress
import com.feerlessleadr.dockge.core.StepState
import com.feerlessleadr.dockge.data.ActionOutcome
import com.feerlessleadr.dockge.data.StackAction
import com.feerlessleadr.dockge.ui.instances.ActionUi

private val ROW_HEIGHT = 22.dp

/**
 * The action sheet: a fixed-size screen (nothing scrolls while the action runs) with a progress bar, the phase, the
 * last few items and one line of detail. The raw terminal output is one tap away. The percentage comes from what
 * compose prints; when it prints nothing usable the bar stays indeterminate.
 */
@Composable
fun ActionProgressContent(
    ui: ActionUi,
    title: String,
    /** How many items a finished run would show (services and containers of the stack), or null when unknown. */
    expected: Int?,
    onClose: () -> Unit,
) {
    val state = ui.state
    val view = remember(state.output, ui.action, expected) {
        ComposeProgress.parse(state.output, twoPhase = ui.action == StackAction.Update, expected = expected)
    }
    val failed = state.outcome is ActionOutcome.Failed
    val succeeded = state.outcome == ActionOutcome.Done || state.outcome == ActionOutcome.RolloutPending
    val target: Float? = if (succeeded) 1f else view.fraction
    val animated by animateFloatAsState(target ?: 0f, label = "progress")
    var showFull by rememberSaveable { mutableStateOf(false) }
    // A failure is the moment the raw output matters most, so open it then.
    LaunchedEffect(failed) { if (failed) showFull = true }

    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        (state.outcome as? ActionOutcome.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }

        if (target == null && state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth(),
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    succeeded -> "Done"
                    failed -> "Stopped with an error"
                    state.output.isBlank() -> "Waiting for the server..."
                    else -> view.phase
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (target != null) Text("${(animated * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        }

        if (!showFull) {
            // Fixed height: rows come and go but the sheet never changes size.
            Column(Modifier.fillMaxWidth().height(ROW_HEIGHT * ComposeProgress.MAX_ITEMS)) {
                view.items.forEach { item ->
                    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (item.state) { StepState.Done -> "✔"; StepState.Failed -> "✘"; StepState.Working -> "…" },
                            Modifier.padding(end = 8.dp),
                            color = when (item.state) {
                                StepState.Done -> MaterialTheme.colorScheme.primary
                                StepState.Failed -> MaterialTheme.colorScheme.error
                                StepState.Working -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            "${item.kind} ${item.name}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(item.verb, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                view.detail.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
            )
        } else {
            val scroll = rememberScrollState()
            LaunchedEffect(state.output) { scroll.scrollTo(scroll.maxValue) }
            Text(
                state.output.ifBlank { if (state.running) "Waiting for output..." else "No output." },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT * (ComposeProgress.MAX_ITEMS + 1)).heightIn(max = 400.dp).verticalScroll(scroll),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showFull = !showFull }) { Text(if (showFull) "Show progress" else "Show full output") }
            TextButton(onClick = onClose) { Text(if (state.running) "Hide" else "Close") }
        }
    }
}
