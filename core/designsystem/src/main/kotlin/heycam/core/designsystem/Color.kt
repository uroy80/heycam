package heycam.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The Broadsheet palette: paper, ink and one editorial red (theme.json `color.light` / `color.dark`). */
@Immutable
data class HeycamColors(
    /** Screen background, app bars, sheets. */
    val paper: Color,
    /** Inputs, segmented options, page cards. */
    val sheet: Color,
    /** The photo well behind a page. */
    val canvas: Color,
    /** Skeletons and transparency. */
    val checker: Color,
    /** Text, thick rules, primary buttons, selected choices. */
    val ink: Color,
    /** Ledes and secondary copy. */
    val ink2: Color,
    /** Labels, captions, hints (at least 4.5:1 on paper, sheet and canvas). */
    val muted: Color,
    /** Decorative hairline; never a control boundary. */
    val rule: Color,
    /** Control boundary (at least 3:1 on paper and sheet; on canvas use [ink]). */
    val line: Color,
    /** Editorial red: kicker, current item, focus, destructive, error. Always paired with text or an icon. */
    val accent: Color,
    /** Type on [ink] or [accent] fills. */
    val onInk: Color,
    val warn: Color,
    val success: Color,
    val accentWash: Color,
    val warnWash: Color,
    val hover: Color,
    val press: Color,
    val selection: Color,
    val scrim: Color,
    val isDark: Boolean,
)

/** Day paper: the light edition. */
val DayPaperColors = HeycamColors(
    paper = Color(0xFFF4EFE4), sheet = Color(0xFFFBF8F1), canvas = Color(0xFFE9E3D6), checker = Color(0xFFE2DCCF),
    ink = Color(0xFF141210), ink2 = Color(0xFF3B372F), muted = Color(0xFF625C51),
    rule = Color(0xFFCFC7B6), line = Color(0xFF8B8374),
    accent = Color(0xFFB4141B), onInk = Color(0xFFF4EFE4),
    warn = Color(0xFF8A5200), success = Color(0xFF2D6A3A),
    accentWash = Color(0xFFEFDDD4), warnWash = Color(0xFFEAE1CF),
    hover = Color(0xFF141210).copy(alpha = 0.06f), press = Color(0xFF141210).copy(alpha = 0.11f),
    selection = Color(0xFFB4141B).copy(alpha = 0.22f), scrim = Color(0xFF141210).copy(alpha = 0.42f),
    isDark = false,
)

/** Night ink: the dark edition. */
val NightInkColors = HeycamColors(
    paper = Color(0xFF141311), sheet = Color(0xFF1C1A17), canvas = Color(0xFF0D0C0B), checker = Color(0xFF24221E),
    ink = Color(0xFFEFE9DC), ink2 = Color(0xFFD3CCBD), muted = Color(0xFFA49D8F),
    rule = Color(0xFF38342E), line = Color(0xFF78705F),
    accent = Color(0xFFFF5E52), onInk = Color(0xFF141311),
    warn = Color(0xFFE7A94A), success = Color(0xFF7FC48B),
    accentWash = Color(0xFF271916), warnWash = Color(0xFF272116),
    hover = Color(0xFFEFE9DC).copy(alpha = 0.06f), press = Color(0xFFEFE9DC).copy(alpha = 0.11f),
    selection = Color(0xFFFF5E52).copy(alpha = 0.30f), scrim = Color(0xFF000000).copy(alpha = 0.60f),
    isDark = true,
)

/** Anything drawn over a camera preview is the night edition in both themes, so the picture stays the brightest thing. */
object CameraColors {
    val surround = Color(0xFF0D0C0B)
    /** Only for things over the live preview. */
    val chrome = Color(0xFF0D0C0B).copy(alpha = 0.86f)
    val ink = Color(0xFFEFE9DC)
    val muted = Color(0xFFA49D8F)
    /** Marks only (edges, badges, countdown); never text. */
    val accent = Color(0xFFFF5E52)
    /** Crop-mark corners on a detected page. */
    val marks = Color(0xFFF4EFE4)
    val shutterRing = Color(0xFFEFE9DC)
    val shutterDisc = Color(0xFFF4EFE4)
}

/** Feeds stock Material 3 components. Dynamic colour is deliberately off: the palette is the brand. */
fun HeycamColors.toColorScheme(): ColorScheme {
    val other = if (isDark) DayPaperColors else NightInkColors
    return if (isDark) {
        darkColorScheme(
            primary = ink, onPrimary = onInk, primaryContainer = ink, onPrimaryContainer = onInk,
            inversePrimary = other.accent,
            secondary = accent, onSecondary = onInk, secondaryContainer = accentWash, onSecondaryContainer = ink,
            tertiary = warn, onTertiary = onInk, tertiaryContainer = warnWash, onTertiaryContainer = ink,
            background = paper, onBackground = ink,
            surface = paper, onSurface = ink, surfaceVariant = sheet, onSurfaceVariant = muted,
            surfaceTint = paper, inverseSurface = ink, inverseOnSurface = paper,
            error = accent, onError = onInk, errorContainer = accentWash, onErrorContainer = ink,
            outline = line, outlineVariant = rule, scrim = Color.Black,
            surfaceBright = checker, surfaceDim = canvas,
            surfaceContainerLowest = canvas, surfaceContainerLow = paper, surfaceContainer = paper,
            surfaceContainerHigh = sheet, surfaceContainerHighest = checker,
        )
    } else {
        lightColorScheme(
            primary = ink, onPrimary = onInk, primaryContainer = ink, onPrimaryContainer = onInk,
            inversePrimary = other.accent,
            secondary = accent, onSecondary = onInk, secondaryContainer = accentWash, onSecondaryContainer = ink,
            tertiary = warn, onTertiary = onInk, tertiaryContainer = warnWash, onTertiaryContainer = ink,
            background = paper, onBackground = ink,
            surface = paper, onSurface = ink, surfaceVariant = canvas, onSurfaceVariant = muted,
            surfaceTint = paper, inverseSurface = ink, inverseOnSurface = paper,
            error = accent, onError = onInk, errorContainer = accentWash, onErrorContainer = ink,
            outline = line, outlineVariant = rule, scrim = Color(0xFF141210),
            surfaceBright = sheet, surfaceDim = canvas,
            surfaceContainerLowest = sheet, surfaceContainerLow = paper, surfaceContainer = paper,
            surfaceContainerHigh = canvas, surfaceContainerHighest = checker,
        )
    }
}
