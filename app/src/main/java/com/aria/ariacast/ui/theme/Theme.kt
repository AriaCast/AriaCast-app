package com.aria.ariacast.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.aria.ariacast.ThemeUtils

// Static fallback for Android 10-11, where Material You is unavailable. Everywhere else the
// scheme is derived from the user's wallpaper; AriaCast does not impose a brand color.
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF3F5F90), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF555F71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3F8), onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF6E5676), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7D8FF), onTertiaryContainer = Color(0xFF27132F),
    background = Color(0xFFF9F9FF), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4), surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA8C8FF), onPrimary = Color(0xFF07305F),
    primaryContainer = Color(0xFF254777), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBDC7DC), onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3E4758), onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFFDABDE2), onTertiary = Color(0xFF3D2845),
    tertiaryContainer = Color(0xFF553F5D), onTertiaryContainer = Color(0xFFF7D8FF),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E), onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474E),
)

/** Semantic status colors that must not follow the wallpaper (success/warning read the same everywhere). */
object StatusColors {
    val Connected = Color(0xFF2E9D57)
    val Available = Color(0xFF8E9099)
    val Warning = Color(0xFFE0A100)
}

// Material type scale on the system face. Titles and headlines take one weight step up for a
// firmer hierarchy; nothing is hand-sized per screen.
private val AriaTypography = Typography().let { t ->
    val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Normal, lineHeightStyle = trim),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Normal),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Normal),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.Medium),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun AriaTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val mode by ThemeUtils.mode.collectAsState()
    val darkTheme = when (mode) {
        ThemeUtils.MODE_NIGHT_YES -> true
        ThemeUtils.MODE_NIGHT_NO -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, typography = AriaTypography, content = content)
}

/** Tabular figures for live numbers (volume, delay, counters) so they don't jitter. */
val StatTextStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, fontFeatureSettings = "tnum")
