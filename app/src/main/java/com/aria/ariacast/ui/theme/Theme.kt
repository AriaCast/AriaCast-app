package com.aria.ariacast.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// AriaCast brand palette (same values as res/values/colors.xml), used when dynamic color
// is unavailable (Android 10-11) or turned off.
private val BrandLight = lightColorScheme(
    primary = Color(0xFFB90063), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DF), onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFF77565A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9DF), onSecondaryContainer = Color(0xFF2C1518),
    tertiary = Color(0xFF7A592D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB4), onTertiaryContainer = Color(0xFF2A1900),
    background = Color(0xFFFFFBFF), onBackground = Color(0xFF201A1B),
    surface = Color(0xFFFFFBFF), onSurface = Color(0xFF201A1B),
    surfaceVariant = Color(0xFFF4DDDF), onSurfaceVariant = Color(0xFF524345),
    outline = Color(0xFF847375),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFFFB1C2), onPrimary = Color(0xFF65002F),
    primaryContainer = Color(0xFF8E0049), onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFE5BDC1), onSecondary = Color(0xFF43292D),
    secondaryContainer = Color(0xFF5D3F43), onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFECC08C), onTertiary = Color(0xFF452B04),
    tertiaryContainer = Color(0xFF5F4118), onTertiaryContainer = Color(0xFFFFDDB4),
    background = Color(0xFF201A1B), onBackground = Color(0xFFECE0E0),
    surface = Color(0xFF201A1B), onSurface = Color(0xFFECE0E0),
    surfaceVariant = Color(0xFF524345), onSurfaceVariant = Color(0xFFD7C1C3),
    outline = Color(0xFF9F8C8E),
)

/** Status colors for receiver dots (Discussion #11): connected, available, offline. */
object StatusColors {
    val Connected = Color(0xFF2E9D57)
    val Available = Color(0xFFD9534F)
}

private val AriaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AriaTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun AriaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = scheme, shapes = AriaShapes, typography = AriaTypography, content = content)
}

/** Monospace-ish numeric style for live stats. */
val StatTextStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.sp)
