package app.zephyr.fitness.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Zephyr's palette.
 *
 * Dark by default and deliberately so: the app is opened at 6am before a run and at 10pm after
 * dinner, and a white screen at either hour is hostile. The accent is a single high-energy mint
 * used sparingly — it means "this is your progress" and nothing else, so the eye learns it.
 *
 * Note what is missing: red. Going over budget is amber, not red, because red reads as an error and
 * eating dinner is not an error. The app is allowed to inform; it is not allowed to scold.
 */
object ZephyrColors {
    val Ink = Color(0xFF0B0F14)
    val Surface = Color(0xFF131A22)
    val SurfaceElevated = Color(0xFF1C2530)
    val Outline = Color(0xFF2C3846)

    val Mint = Color(0xFF00E5A0)
    val MintDim = Color(0xFF00B37D)
    val Ember = Color(0xFFFF7A45)
    val Violet = Color(0xFF7C6BFF)
    val Amber = Color(0xFFFFB454)
    val Sky = Color(0xFF4FC3F7)

    val TextPrimary = Color(0xFFF2F6FA)
    val TextSecondary = Color(0xFF9AA7B5)
    val TextTertiary = Color(0xFF66727F)
}

private val DarkScheme = darkColorScheme(
    primary = ZephyrColors.Mint,
    onPrimary = ZephyrColors.Ink,
    primaryContainer = ZephyrColors.MintDim,
    onPrimaryContainer = ZephyrColors.Ink,
    secondary = ZephyrColors.Ember,
    onSecondary = ZephyrColors.Ink,
    tertiary = ZephyrColors.Violet,
    onTertiary = ZephyrColors.TextPrimary,
    background = ZephyrColors.Ink,
    onBackground = ZephyrColors.TextPrimary,
    surface = ZephyrColors.Surface,
    onSurface = ZephyrColors.TextPrimary,
    surfaceVariant = ZephyrColors.SurfaceElevated,
    onSurfaceVariant = ZephyrColors.TextSecondary,
    outline = ZephyrColors.Outline,
    error = ZephyrColors.Amber,
    onError = ZephyrColors.Ink,
)

private val LightScheme = lightColorScheme(
    primary = ZephyrColors.MintDim,
    onPrimary = Color.White,
    secondary = ZephyrColors.Ember,
    tertiary = ZephyrColors.Violet,
    background = Color(0xFFF7F9FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDF1F5),
    error = Color(0xFFB26A00),
)

/**
 * Type scale built for glanceability. The hero numbers are enormous on purpose — the single most
 * common interaction with this app is a two-second glance to answer "how am I doing", and that
 * answer should be readable at arm's length without focusing.
 */
val ZephyrTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 72.sp,
            letterSpacing = (-2).sp,
        ),
        displayMedium = displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
    )
}

/** Numeric style for stat readouts, where digits must not jitter as values change. */
val StatNumberStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    letterSpacing = (-0.5).sp,
)

@Composable
fun ZephyrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = ZephyrTypography,
        content = content,
    )
}
