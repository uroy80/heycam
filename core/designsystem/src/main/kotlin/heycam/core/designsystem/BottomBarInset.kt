package heycam.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The bottom bars on screen now (Home's scan bar, a document's page tools, Review's controls), so that snackbars the
 * app shell shows above every screen clear them. A bar reports its measured height through [ReportBottomBar]; the
 * shell provides one [BottomBarInset] with [LocalBottomBarInset] and pads its snackbar host by [height].
 */
@Stable
class BottomBarInset {
    // Keyed per bar: while one screen replaces another, both bars are briefly on screen.
    private val heights = mutableStateMapOf<Any, Dp>()

    /** The tallest bar on screen, with the navigation-bar padding it draws under itself; 0 dp when there is none. */
    val height: Dp get() = heights.values.maxOrNull() ?: 0.dp

    internal fun report(key: Any, height: Dp) {
        heights[key] = height
    }

    internal fun clear(key: Any) {
        heights.remove(key)
    }
}

/** Null outside the app shell (previews, screen tests): bars then report nothing. */
val LocalBottomBarInset = staticCompositionLocalOf<BottomBarInset?> { null }

/** Wraps a screen's bottom bar so its height reaches [LocalBottomBarInset]; the height goes back to 0 when it leaves. */
@Composable
fun ReportBottomBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val inset = LocalBottomBarInset.current
    if (inset == null) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val key = remember { Any() }
    DisposableEffect(inset, key) { onDispose { inset.clear(key) } }
    Box(modifier.onSizeChanged { inset.report(key, with(density) { it.height.toDp() }) }) { content() }
}
