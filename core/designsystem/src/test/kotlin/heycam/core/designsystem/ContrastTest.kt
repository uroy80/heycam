package heycam.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * WCAG 2.x contrast for every colour pair the components print, in both editions: text at least 4.5:1, control
 * boundaries at least 3:1 (theme.json contrastChecks, plus the pairs the shared components add). Light `line` on
 * `canvas` is 2.93:1, which is why controls sitting on canvas use ink for their border.
 */
class ContrastTest {
    private fun ratio(fg: Color, bg: Color): Double {
        val a = fg.luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b)
    }

    private fun HeycamColors.name() = if (isDark) "night ink" else "day paper"

    private fun check(colors: HeycamColors, pair: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertWithMessage("${colors.name()}: $pair is %.2f:1, needs %.1f:1".format(r, min)).that(r).isAtLeast(min)
    }

    @Test
    fun textMeetsFourAndAHalfToOne() {
        listOf(DayPaperColors, NightInkColors).forEach { c ->
            val grounds = mapOf("paper" to c.paper, "sheet" to c.sheet, "canvas" to c.canvas)
            grounds.forEach { (ground, bg) ->
                check(c, "ink on $ground", c.ink, bg, 4.5)
                check(c, "ink2 on $ground", c.ink2, bg, 4.5)
                check(c, "muted on $ground", c.muted, bg, 4.5)
                check(c, "accent on $ground", c.accent, bg, 4.5)
            }
            check(c, "onInk on ink (primary button, snackbar, reversed segment)", c.onInk, c.ink, 4.5)
            check(c, "onInk on accent (pressed button, delete target)", c.onInk, c.accent, 4.5)
            check(c, "ink on accentWash (error banner, selected row)", c.ink, c.accentWash, 4.5)
            check(c, "accent on accentWash (Retry in an error banner)", c.accent, c.accentWash, 4.5)
            check(c, "muted on accentWash (meta line of a selected row)", c.muted, c.accentWash, 4.5)
            check(c, "warn on paper", c.warn, c.paper, 4.5)
            check(c, "warn on warnWash", c.warn, c.warnWash, 4.5)
            check(c, "success on paper", c.success, c.paper, 4.5)
            val inverseAccent = if (c.isDark) DayPaperColors.accent else NightInkColors.accent
            check(c, "the other edition's accent on ink (snackbar action)", inverseAccent, c.ink, 4.5)
        }
    }

    @Test
    fun controlBoundariesMeetThreeToOne() {
        listOf(DayPaperColors, NightInkColors).forEach { c ->
            check(c, "line on paper", c.line, c.paper, 3.0)
            check(c, "line on sheet", c.line, c.sheet, 3.0)
            check(c, "ink on canvas (borders over canvas)", c.ink, c.canvas, 3.0)
        }
        check(NightInkColors, "line on canvas", NightInkColors.line, NightInkColors.canvas, 3.0)
    }

    @Test
    fun cameraChromeKeepsTextReadableOverThePreview() {
        val r = ratio(CameraColors.ink, CameraColors.surround)
        assertWithMessage("camera ink on surround is %.2f:1".format(r)).that(r).isAtLeast(4.5)
    }
}
