package heycam.feature.document

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat

/** Draws [content] as if the system font size were [scale] (Settings > Display > Font size). */
@Composable
fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
}

/** A text node laid out whole (not cut off, not ellipsised) on at most [lines] line(s). */
fun SemanticsNodeInteraction.assertTextFits(lines: Int = 1): SemanticsNodeInteraction {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
    val layout = results.single()
    assertThat(layout.didOverflowHeight).isFalse()
    assertThat((0 until layout.lineCount).none { layout.isLineEllipsized(it) }).isTrue()
    assertThat(layout.lineCount).isAtMost(lines)
    return this
}
