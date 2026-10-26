package heycam.core.designsystem

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Appearance setting; :app maps the stored ThemeSetting onto this. */
enum class ThemeMode { System, DayPaper, NightInk }

val LocalHeycamColors = staticCompositionLocalOf { DayPaperColors }
val LocalHeycamType = staticCompositionLocalOf { BroadsheetType }

/** True when the user turned animations off (Settings > Accessibility > Remove animations): use cross-fades only. */
val LocalReducedMotion = staticCompositionLocalOf { false }

object Heycam {
    val colors: HeycamColors
        @Composable @ReadOnlyComposable get() = LocalHeycamColors.current
    val type: HeycamType
        @Composable @ReadOnlyComposable get() = LocalHeycamType.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/** Square print: every Material 3 shape slot is 2 dp. Sheets and dialogs are square (see [SheetFrame]). */
val BroadsheetShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

/** Sizes from theme.json `size`. Touch targets are 48 dp with 8 dp between them. */
object HeycamSize {
    val touch: Dp = 48.dp
    val touchSpacing: Dp = 8.dp
    val gutter: Dp = 16.dp
    val gutterWide: Dp = 24.dp
    /** Content column on screens 600 dp and wider. */
    val maxContentWidth: Dp = 640.dp
    val masthead: Dp = 56.dp
    val dateline: Dp = 26.dp
    val tabRow: Dp = 48.dp
    val appBar: Dp = 56.dp
    val appBarWithKicker: Dp = 72.dp
    val icon: Dp = 24.dp
    val button: Dp = 48.dp
    val buttonLarge: Dp = 56.dp
    val scanBar: Dp = 56.dp
    val segment: Dp = 48.dp
    val listRow: Dp = 76.dp
    val toolRow: Dp = 64.dp
    val settingsRow: Dp = 56.dp
    val textField: Dp = 52.dp
    val chip: Dp = 32.dp
    val thumbRow = DpSize(44.dp, 60.dp)
    val thumbLead = DpSize(104.dp, 140.dp)
    val filterThumb = DpSize(64.dp, 84.dp)
    val pageGridGap: Dp = 12.dp
    val hair: Dp = 1.dp
    val ruleMedium: Dp = 2.dp
    val ruleThick: Dp = 3.dp
}

object HeycamMotion {
    val easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
    const val FAST = 100
    const val BASE = 160
    const val SLOW = 240
}

/**
 * The Broadsheet theme: colours, type, square shapes and a flat ink press overlay, for both heycam components and
 * stock Material 3 ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeycamTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.DayPaper -> false
        ThemeMode.NightInk -> true
    }
    val colors = if (dark) NightInkColors else DayPaperColors
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val ripple = remember(colors) {
        RippleConfiguration(
            color = colors.ink,
            rippleAlpha = RippleAlpha(draggedAlpha = 0.08f, focusedAlpha = 0.11f, hoveredAlpha = 0.06f, pressedAlpha = 0.11f),
        )
    }
    CompositionLocalProvider(
        LocalHeycamColors provides colors,
        LocalHeycamType provides BroadsheetType,
        LocalReducedMotion provides reducedMotion,
        LocalRippleConfiguration provides ripple,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = BroadsheetMaterialTypography,
            shapes = BroadsheetShapes,
        ) {
            // Selected text (recognised text, fields) uses the accent wash and accent handles.
            val selection = remember(colors) { TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.selection) }
            CompositionLocalProvider(LocalTextSelectionColors provides selection, content = content)
        }
    }
}

/**
 * Edge-to-edge is mandatory from targetSdk 36. Call from onCreate before setContent and again when the theme changes:
 * transparent bars with dark icons on Day paper and light icons on Night ink.
 */
fun ComponentActivity.applyBroadsheetSystemBars(dark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}
