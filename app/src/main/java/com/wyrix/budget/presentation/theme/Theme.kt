package com.wyrix.budget.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.wyrix.budget.domain.model.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF55D6A5),
    onPrimary = Color(0xFF06241A),
    primaryContainer = Color(0xFF16453A),
    onPrimaryContainer = Color(0xFFB8F5DC),
    secondary = Color(0xFF6C9EFF),
    onSecondary = Color(0xFF0A1A3A),
    secondaryContainer = Color(0xFF233A66),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFFF5B942),
    onTertiary = Color(0xFF2E2000),
    background = Color(0xFF101318),
    onBackground = Color(0xFFF5F7FA),
    surface = Color(0xFF191F27),
    onSurface = Color(0xFFF5F7FA),
    surfaceVariant = Color(0xFF202833),
    onSurfaceVariant = Color(0xFF9CA8B7),
    surfaceContainerLowest = Color(0xFF0D1014),
    surfaceContainerLow = Color(0xFF151A21),
    surfaceContainer = Color(0xFF191F27),
    surfaceContainerHigh = Color(0xFF202833),
    surfaceContainerHighest = Color(0xFF28313D),
    error = Color(0xFFF16C75),
    onError = Color(0xFF3B0A0E),
    outline = Color(0xFF3A4554),
    outlineVariant = Color(0xFF2A3340),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E8F6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC4F2E1),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF2F63D6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6FF),
    onSecondaryContainer = Color(0xFF0B2459),
    tertiary = Color(0xFF9A6B00),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF101318),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101318),
    surfaceVariant = Color(0xFFE9EEF4),
    onSurfaceVariant = Color(0xFF5A6675),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F4F8),
    surfaceContainerHigh = Color(0xFFE9EEF4),
    surfaceContainerHighest = Color(0xFFE1E7EF),
    error = Color(0xFFC7343F),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFFB3BDC9),
    outlineVariant = Color(0xFFD8DFE8),
)

private val WyrixTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
)

private val WyrixShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun WyrixTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = WyrixTypography,
        shapes = WyrixShapes,
        content = content,
    )
}

/** Distinct, colour-blind-friendly-ish accents for charts; the legend always names every slice. */
@Composable
fun chartColors(): List<Color> = com.wyrix.budget.domain.model.ColorPalette.colors.map { Color(it) }
