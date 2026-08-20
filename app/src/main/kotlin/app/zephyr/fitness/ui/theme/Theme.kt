package app.zephyr.fitness.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Zephyr's palette — light, bold, colour-blocked.
 *
 * Every hue is assigned exactly one job and never reused for another: violet is your energy budget,
 * sky is steps, green is protein, orange is what you burned, pink is your streak. Learn five colours
 * once and the screen becomes readable at a glance instead of word by word — which matters for a
 * screen opened twenty times a day.
 *
 * Note what is missing: red. Going over budget renders amber, because red reads as an error and
 * eating dinner is not an error. The app is allowed to inform; it is not allowed to scold.
 *
 * These values are kept identical to `prototype/styles.css`, which is where the design was actually
 * settled by using it.
 */
object Z {
    val Page = Color(0xFFF6F4FD)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF14131A)
    val Muted = Color(0xFF78748A)
    val Faint = Color(0xFFA5A1B4)
    val Line = Color(0xFFE8E4F4)

    val Violet = Color(0xFF7B61FF)
    val VioletSoft = Color(0xFFEDE9FF)
    val VioletInk = Color(0xFF3B2A9E)

    val Orange = Color(0xFFFF9F43)
    val OrangeSoft = Color(0xFFFFF0DE)
    val OrangeInk = Color(0xFFA85B04)

    val Sky = Color(0xFF4FC0F0)
    val SkySoft = Color(0xFFE1F4FD)
    val SkyInk = Color(0xFF0A5C86)

    val Green = Color(0xFF21C795)
    val GreenSoft = Color(0xFFDFF7EF)
    val GreenInk = Color(0xFF05674C)

    val Pink = Color(0xFFFF7BC0)
    val PinkSoft = Color(0xFFFFE6F3)
    val PinkInk = Color(0xFFA3216C)

    /** Over budget. Amber, never red. */
    val Warn = Color(0xFFE8890C)
    val WarnSoft = Color(0xFFFFEFD6)
    val WarnInk = Color(0xFF8A5300)

    val Nav = Color(0xFF17161D)

    /** Ring accents drawn on the violet hero card. */
    val RingMint = Color(0xFFB9FFE6)
    val RingAmber = Color(0xFFFFE0AE)

    val CardRadius = 28.dp
    val TileRadius = 22.dp
}

private val Scheme = lightColorScheme(
    primary = Z.Violet,
    onPrimary = Color.White,
    primaryContainer = Z.VioletSoft,
    onPrimaryContainer = Z.VioletInk,
    secondary = Z.Orange,
    onSecondary = Color.White,
    tertiary = Z.Sky,
    onTertiary = Color.White,
    background = Z.Page,
    onBackground = Z.Ink,
    surface = Z.Card,
    onSurface = Z.Ink,
    surfaceVariant = Z.VioletSoft,
    onSurfaceVariant = Z.Muted,
    outline = Z.Line,
    error = Z.Warn,
    onError = Color.White,
)

/**
 * Type scale built for glanceability, with weights pushed heavier than Material's defaults. The
 * hero numbers are enormous on purpose: the most common interaction with this app is a two-second
 * glance to answer "how am I doing", and that answer should read at arm's length.
 */
val ZephyrTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 58.sp,
            letterSpacing = (-2.5).sp,
        ),
        displayMedium = displayMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.4).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 15.sp),
        bodyMedium = bodyMedium.copy(fontSize = 13.5.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Bold),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
    )
}

@Composable
fun ZephyrTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Light ground means dark status bar icons.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = Scheme,
        typography = ZephyrTypography,
        content = content,
    )
}
