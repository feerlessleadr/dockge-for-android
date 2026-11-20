package com.feerlessleadr.dockge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Brand = Color(0xFF1D63ED)
private val LightColors = lightColorScheme(primary = Brand)
private val DarkColors = darkColorScheme(primary = Color(0xFF8FB3FF))

/** Success and warning have no slot in the Material color scheme, so they live here (error uses the scheme's own). */
@Immutable
class StatusColors(val ok: Color, val okContainer: Color, val warn: Color, val warnContainer: Color)

private val LightStatus = StatusColors(Color(0xFF1B7F4B), Color(0xFFD3EFDD), Color(0xFF8A5100), Color(0xFFFFE3B5))
private val DarkStatus = StatusColors(Color(0xFF8FD9AC), Color(0xFF12402A), Color(0xFFFFC875), Color(0xFF4A2F00))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

object DockgeColors {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

@Composable
fun DockgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
