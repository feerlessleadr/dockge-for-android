package com.feerlessleadr.dockge.ui.theme

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// The app's own palette (blue brand). It is the default; wallpaper colors are an opt-in in Settings.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1D63ED), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF), onPrimaryContainer = Color(0xFF00164A),
    secondary = Color(0xFF565F71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6FF), onSecondaryContainer = Color(0xFF00164A),
    tertiary = Color(0xFF8A5100), onTertiary = Color.White,
    background = Color(0xFFFAF9FD), onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF9FD), onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE2E2EC), onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F4FA),
    surfaceContainer = Color(0xFFF1EFF6), surfaceContainerHigh = Color(0xFFEBE9F0), surfaceContainerHighest = Color(0xFFE5E3EA),
    outline = Color(0xFF777680), outlineVariant = Color(0xFFC7C6D0),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB3FF), onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF1B3C85), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFBFC6DC), onSecondary = Color(0xFF293041),
    secondaryContainer = Color(0xFF1B3C85), onSecondaryContainer = Color(0xFFDCE6FF),
    tertiary = Color(0xFFFFC875), onTertiary = Color(0xFF4A2F00),
    background = Color(0xFF121318), onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC7C6D0),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF17181D),
    surfaceContainer = Color(0xFF1B1B21), surfaceContainerHigh = Color(0xFF22222A), surfaceContainerHighest = Color(0xFF2A2A32),
    outline = Color(0xFF918F9A), outlineVariant = Color(0xFF46464F),
)

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

/** Whether to use the wallpaper colors (Android 12 and newer) instead of the app palette. Off by default. */
object ThemePrefs {
    private const val FILE = "ui"
    private const val KEY = "dynamic_color"
    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var dynamic by mutableStateOf(false)
        private set

    fun load(context: Context) {
        dynamic = runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false) }.getOrDefault(false)
    }

    fun set(context: Context, value: Boolean) {
        dynamic = value
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply() }
    }
}

@Composable
fun DockgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        ThemePrefs.dynamic && ThemePrefs.supported ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
