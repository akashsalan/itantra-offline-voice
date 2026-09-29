package org.itantra.app

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Shared visual tokens only; no settings, speech or transport state lives here. */
internal object AppDesign {
    val Hero = RoundedCornerShape(28.dp)
    val Card = RoundedCornerShape(20.dp)
    val Control = RoundedCornerShape(14.dp)
    val Pill = RoundedCornerShape(100.dp)
    val IncomingBubble = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val OutgoingBubble = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    val ToolbarAction = RoundedCornerShape(12.dp)
    val PttWidth = 124.dp
    val PttHeight = 76.dp
    const val MotionMs = 180
}

@Composable internal fun StatusTag(label: String, warning: Boolean = false) {
    Surface(shape = AppDesign.Control,
        color = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (warning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable internal fun ItantraTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "DARK" || (theme == "SYSTEM" && isSystemInDarkTheme())
    val colours = if (dark) darkColorScheme(
        primary = Color(0xFF63E6D5), onPrimary = Color(0xFF003832),
        primaryContainer = Color(0xFF174E49), onPrimaryContainer = Color(0xFFC7F2EA),
        secondary = Color(0xFFB7CBC8), onSecondary = Color(0xFF223532),
        secondaryContainer = Color(0xFF304C45), onSecondaryContainer = Color(0xFFD5EDE6),
        background = Color(0xFF071A1D), surface = Color(0xFF102629),
        onBackground = Color(0xFFE1F2EF), onSurface = Color(0xFFE1F2EF),
        surfaceVariant = Color(0xFF294043), onSurfaceVariant = Color(0xFFB3C9C6),
        surfaceContainer = Color(0xFF142E30), surfaceContainerLow = Color(0xFF102629),
        surfaceContainerLowest = Color(0xFF071A1D), surfaceContainerHighest = Color(0xFF294043),
        surfaceContainerHigh = Color(0xFF20393C), outline = Color(0xFF879F9C),
        outlineVariant = Color(0xFF3C5557), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF730C10), onErrorContainer = Color(0xFFFFDAD6),
        tertiary = Color(0xFFFFCC80), onTertiary = Color(0xFF442A00),
        tertiaryContainer = Color(0xFF533B17), onTertiaryContainer = Color(0xFFFFE2B4)
    ) else lightColorScheme(
        primary = Color(0xFF006B63), onPrimary = Color.White,
        primaryContainer = Color(0xFFC7F2EA), onPrimaryContainer = Color(0xFF003D36),
        secondary = Color(0xFF425C57), onSecondary = Color.White,
        secondaryContainer = Color(0xFFD6EAE4), onSecondaryContainer = Color(0xFF173B33),
        background = Color(0xFFF3F7F6), surface = Color.White,
        onBackground = Color(0xFF102A2E), onSurface = Color(0xFF102A2E),
        surfaceVariant = Color(0xFFE2ECE9), onSurfaceVariant = Color(0xFF526667),
        surfaceContainer = Color(0xFFEBF2EF), surfaceContainerLow = Color.White,
        surfaceContainerLowest = Color.White, surfaceContainerHighest = Color(0xFFE2ECE9),
        surfaceContainerHigh = Color(0xFFE6EFEB), outline = Color(0xFF718783),
        outlineVariant = Color(0xFFC0D2CC), error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        tertiary = Color(0xFF805000), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDEA6), onTertiaryContainer = Color(0xFF2B1900)
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val defaults = Typography()
    MaterialTheme(colorScheme = colours, shapes = Shapes(extraSmall = AppDesign.Control, small = AppDesign.Control,
        medium = AppDesign.Card, large = AppDesign.Hero, extraLarge = AppDesign.Hero),
        // Headings get slightly negative tracking so they read as set rather than
        // typed; body sizes are unchanged so large-font and TalkBack layouts, and
        // the existing presentation tests, behave exactly as before.
        typography = defaults.copy(
        headlineMedium = defaults.headlineMedium.copy(fontSize = 28.sp, lineHeight = 35.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = defaults.headlineSmall.copy(fontSize = 23.sp, lineHeight = 30.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = defaults.titleLarge.copy(fontSize = 26.sp, lineHeight = 33.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleMedium = defaults.titleMedium.copy(fontSize = 18.sp, lineHeight = 25.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        bodyLarge = defaults.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = defaults.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodySmall = defaults.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = defaults.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold),
        labelMedium = defaults.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.Medium)
    ), content = content)
}

