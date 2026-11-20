package com.feerlessleadr.dockge.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feerlessleadr.dockge.ui.theme.DockgeColors

/** The meaning of a tile or chip; it decides the tonal container and content color. */
enum class Tone { Ok, Warn, Error, Info, Neutral }

@Composable
fun Tone.container(): Color = when (this) {
    Tone.Ok -> DockgeColors.status.okContainer
    Tone.Warn -> DockgeColors.status.warnContainer
    Tone.Error -> MaterialTheme.colorScheme.errorContainer
    Tone.Info -> MaterialTheme.colorScheme.primaryContainer
    Tone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
fun Tone.content(): Color = when (this) {
    Tone.Ok -> DockgeColors.status.ok
    Tone.Warn -> DockgeColors.status.warn
    Tone.Error -> MaterialTheme.colorScheme.onErrorContainer
    Tone.Info -> MaterialTheme.colorScheme.onPrimaryContainer
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** A rounded square with an icon, tinted by tone. */
@Composable
fun StatusTile(painter: Painter, tone: Tone, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).background(tone.container(), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter, contentDescription = null, tint = tone.content(), modifier = Modifier.size(22.dp))
    }
}

@Composable
fun StatusTile(icon: ImageVector, tone: Tone, modifier: Modifier = Modifier) = StatusTile(rememberVectorPainter(icon), tone, modifier)

@Composable
fun StatusTile(@DrawableRes icon: Int, tone: Tone, modifier: Modifier = Modifier) = StatusTile(painterResource(icon), tone, modifier)

@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = tone.container()) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = tone.content(),
            maxLines = 1,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** The list card used everywhere: a tonal container, optionally clickable. Content is laid out in a row. */
@Composable
fun ListCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = CardDefaults.cardColors(containerColor = container)
    val inner: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(PaddingValues(horizontal = 14.dp, vertical = 12.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
    if (onClick != null) Card(onClick, modifier.fillMaxWidth(), enabled = enabled, shape = shape, colors = colors) { inner() }
    else Card(modifier.fillMaxWidth(), shape = shape, colors = colors) { inner() }
}

/** The screen title: compact, directly under the status bar (a back arrow row above it when [onBack] is given). */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        if (onBack != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                actions()
            }
        }
        Text(
            title,
            Modifier.padding(horizontal = 4.dp, vertical = if (onBack == null) 12.dp else 0.dp),
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(it, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
