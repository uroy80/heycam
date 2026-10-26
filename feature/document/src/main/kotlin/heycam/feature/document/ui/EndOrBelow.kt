package heycam.feature.document.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp

/**
 * Two children on one line, the first at the start and the second at the end; when they do not both fit (large font
 * sizes, narrow columns) the second goes on the next line, at [below]. Neither is ever squeezed.
 */
@Composable
internal fun EndOrBelow(modifier: Modifier = Modifier, below: Alignment.Horizontal = Alignment.Start, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = 12.dp.roundToPx()
        val lineGap = 4.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val first = measurables.first().measure(loose)
        val second = measurables.getOrNull(1)?.measure(loose)
        val width = constraints.maxWidth
        when {
            second == null -> {
                val height = maxOf(first.height, constraints.minHeight)
                layout(width, height) { first.placeRelative(0, (height - first.height) / 2) }
            }
            first.width + gap + second.width <= width -> {
                val height = maxOf(first.height, second.height, constraints.minHeight)
                layout(width, height) {
                    first.placeRelative(0, (height - first.height) / 2)
                    second.placeRelative(width - second.width, (height - second.height) / 2)
                }
            }
            else -> {
                val height = maxOf(first.height + lineGap + second.height, constraints.minHeight)
                layout(width, height) {
                    first.placeRelative(0, 0)
                    second.placeRelative(below.align(second.width, width, layoutDirection), first.height + lineGap)
                }
            }
        }
    }
}
