package heycam.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Drag state shared by [PageGridReorder] and [PageReorderBar]. While [isDragging], the screen swaps its bottom
 * toolbar for a [PageReorderBar].
 */
@Stable
class PageReorderState internal constructor(internal val gridState: LazyGridState) {
    /** Index of the lifted page, or null when nothing is being dragged. */
    var draggingIndex: Int? by mutableStateOf(null)
        private set

    /** Where the lifted page would land, or null while it is over the delete target. */
    var targetIndex: Int? by mutableStateOf(null)
        private set

    /** True while the lifted page is over the [PageReorderBar]'s delete target. */
    var overDelete: Boolean by mutableStateOf(false)
        private set

    val isDragging: Boolean get() = draggingIndex != null

    internal var canDelete by mutableStateOf(false)
    internal var pointer by mutableStateOf(Offset.Zero) // container coordinates
    internal var grab = Offset.Zero // pointer minus the lifted cell's top-left
    internal var cellWidthPx by mutableFloatStateOf(0f)
    internal var keys: List<Any> = emptyList()
    internal val cellBounds = mutableMapOf<Any, Rect>() // in root coordinates
    internal var containerOrigin = Offset.Zero
    internal var deleteBounds: Rect? = null

    internal fun lift(local: Offset): Boolean {
        val root = local + containerOrigin
        val index = keys.indexOfFirst { cellBounds[it]?.contains(root) == true }
        if (index < 0) return false
        val bounds = cellBounds.getValue(keys[index])
        grab = root - bounds.topLeft
        cellWidthPx = bounds.width
        pointer = local
        draggingIndex = index
        targetIndex = index
        overDelete = false
        return true
    }

    internal fun moveTo(local: Offset) {
        pointer = local
        retarget()
    }

    internal fun retarget() {
        val root = pointer + containerOrigin
        overDelete = canDelete && deleteBounds?.contains(root) == true
        targetIndex = if (overDelete) null else nearestIndex(root)
    }

    private fun nearestIndex(root: Offset): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        keys.forEachIndexed { i, key ->
            val b = cellBounds[key] ?: return@forEachIndexed
            if (b.contains(root)) return i
            val d = (b.center - root).getDistanceSquared()
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    internal fun drop(): Pair<Int, Int?>? {
        val from = draggingIndex ?: return null
        val result = from to if (overDelete) null else targetIndex
        cancel()
        return result
    }

    internal fun cancel() {
        draggingIndex = null
        targetIndex = null
        overDelete = false
    }
}

@Composable
fun rememberPageReorderState(gridState: LazyGridState = rememberLazyGridState()): PageReorderState =
    remember(gridState) { PageReorderState(gridState) }

/** 3 columns on phones, 4 to 6 on wider screens. */
private object PageColumns : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val widthDp = availableSize / density
        val count = when {
            widthDp < 560 -> 3
            widthDp < 760 -> 4
            widthDp < 960 -> 5
            else -> 6
        }
        val usable = availableSize - spacing * (count - 1)
        val cell = usable / count
        val remainder = usable % count
        return List(count) { cell + if (it < remainder) 1 else 0 }
    }
}

private const val HEADER_KEY = "heycam.reorder.header"

/**
 * The document's page grid. Long-press lifts a page (it tilts, unless animations are off); a 3 dp accent bar marks
 * where it will land, and the grid scrolls when the page nears an edge. Dropping calls [onMove] with list indices,
 * or [onDelete] over the [PageReorderBar]'s delete target. TalkBack users get the same through custom actions on
 * each page: Move earlier, Move later, Move to start, Move to end, Move to position… ([onMoveToPosition]) and
 * Delete page.
 *
 * @param header full-width content scrolled above the pages (meta line, Pages | Text toggle).
 */
@Composable
fun <T> PageGridReorder(
    pages: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    onOpen: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    state: PageReorderState = rememberPageReorderState(),
    onDelete: ((index: Int) -> Unit)? = null,
    onMoveToPosition: ((index: Int) -> Unit)? = null,
    aspectRatio: (T) -> Float = { A4_PORTRAIT_RATIO },
    currentIndex: Int? = null,
    contentPadding: PaddingValues = PaddingValues(HeycamSize.gutter),
    header: (@Composable () -> Unit)? = null,
    page: @Composable BoxScope.(T) -> Unit,
) {
    val c = Heycam.colors
    val reduced = Heycam.reducedMotion
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDelete by rememberUpdatedState(onDelete)
    SideEffect {
        state.keys = pages.map(key)
        state.canDelete = onDelete != null
    }

    val labels = ReorderLabels(
        earlier = stringResource(R.string.hc_move_earlier),
        later = stringResource(R.string.hc_move_later),
        start = stringResource(R.string.hc_move_to_start),
        end = stringResource(R.string.hc_move_to_end),
        position = stringResource(R.string.hc_move_to_position),
        delete = stringResource(R.string.hc_delete_page),
    )

    // Scroll while a lifted page is held near the top or bottom edge (not while it is over the delete target). The
    // loop only runs near an edge, so a page held still mid-grid costs no frames.
    val edge = with(density) { 72.dp.toPx() }
    val maxStep = with(density) { 14.dp.toPx() }
    val scrollDirection by remember(state, edge) {
        derivedStateOf {
            val height = state.gridState.layoutInfo.viewportSize.height
            val y = state.pointer.y
            when {
                !state.isDragging || state.overDelete || height == 0 -> 0
                y < edge -> -1
                y > height - edge -> 1
                else -> 0
            }
        }
    }
    LaunchedEffect(state, scrollDirection) {
        if (scrollDirection == 0) return@LaunchedEffect
        while (true) {
            val height = state.gridState.layoutInfo.viewportSize.height.toFloat()
            val y = state.pointer.y
            val depth = if (scrollDirection < 0) 1f - y / edge else 1f - (height - y) / edge
            state.gridState.scrollBy(scrollDirection * maxStep * depth.coerceIn(0.1f, 1f))
            withFrameNanos { }
            state.retarget()
        }
    }

    Box(
        modifier
            .onGloballyPositioned { state.containerOrigin = it.positionInRoot() }
            .pointerInput(state) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { if (state.lift(it)) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDrag = { change, _ ->
                        if (state.isDragging) {
                            change.consume()
                            state.moveTo(change.position)
                        }
                    },
                    onDragEnd = {
                        state.drop()?.let { (from, to) ->
                            if (to == null) currentOnDelete?.invoke(from) else if (to != from) currentOnMove(from, to)
                        }
                    },
                    onDragCancel = { state.cancel() },
                )
            }
            .drawWithContent {
                drawContent()
                val from = state.draggingIndex ?: return@drawWithContent
                val to = state.targetIndex ?: return@drawWithContent
                if (to == from) return@drawWithContent
                val b = state.cellBounds[state.keys.getOrNull(to)] ?: return@drawWithContent
                val local = b.translate(-state.containerOrigin)
                val gap = HeycamSize.pageGridGap.toPx()
                val before = to < from
                val startIsLeft = direction == LayoutDirection.Ltr
                val x = if (before == startIsLeft) local.left - gap / 2 else local.right + gap / 2
                val w = HeycamSize.ruleThick.toPx()
                // The bar runs down the page frame, not the page number under it.
                drawLine(c.accent, Offset(x, local.top), Offset(x, local.bottom - 18.dp.toPx()), w)
            },
    ) {
        LazyVerticalGrid(
            columns = PageColumns,
            state = state.gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(HeycamSize.pageGridGap),
            verticalArrangement = Arrangement.spacedBy(HeycamSize.pageGridGap + 4.dp),
            userScrollEnabled = !state.isDragging,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) {
                item(key = HEADER_KEY, span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
            }
            itemsIndexed(pages, key = { _, p -> key(p) }, contentType = { _, _ -> "page" }) { i, p ->
                val k = key(p)
                DisposableEffect(k) { onDispose { state.cellBounds.remove(k) } }
                val lifted = state.draggingIndex == i
                val actions = buildList {
                    if (i > 0) add(CustomAccessibilityAction(labels.earlier) { onMove(i, i - 1); true })
                    if (i < pages.lastIndex) add(CustomAccessibilityAction(labels.later) { onMove(i, i + 1); true })
                    if (i > 1) add(CustomAccessibilityAction(labels.start) { onMove(i, 0); true })
                    if (i < pages.lastIndex - 1) add(CustomAccessibilityAction(labels.end) { onMove(i, pages.lastIndex); true })
                    if (onMoveToPosition != null && pages.size > 2) {
                        add(CustomAccessibilityAction(labels.position) { onMoveToPosition(i); true })
                    }
                    if (onDelete != null) add(CustomAccessibilityAction(labels.delete) { onDelete(i); true })
                }
                val ratio = aspectRatio(p)
                PageThumb(
                    number = i + 1,
                    aspectRatio = ratio,
                    current = i == currentIndex,
                    contentDescription = stringResource(R.string.hc_page_of, i + 1, pages.size),
                    modifier = Modifier
                        .onGloballyPositioned { state.cellBounds[k] = it.boundsInRoot() }
                        .drawBehind {
                            if (lifted) {
                                val dash = 5.dp.toPx()
                                drawRect(
                                    color = c.line,
                                    size = Size(size.width, size.width / ratio),
                                    style = Stroke(HeycamSize.hair.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                                )
                            }
                        }
                        .alpha(if (lifted) 0f else 1f)
                        .focusRing()
                        .clickable { onOpen(i) }
                        .semantics { customActions = actions },
                ) { page(p) }
            }
        }

        // The lifted page follows the finger above the grid.
        val from = state.draggingIndex
        if (from != null && from in pages.indices) {
            val item = pages[from]
            val ratio = aspectRatio(item)
            val widthDp = with(density) { state.cellWidthPx.toDp() }
            Box(
                Modifier
                    .offset { (state.pointer - state.grab).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                    .size(widthDp, widthDp / ratio)
                    .graphicsLayer {
                        if (!reduced) {
                            scaleX = 1.04f
                            scaleY = 1.04f
                            rotationZ = -3f
                        }
                        shadowElevation = 8.dp.toPx()
                    }
                    .background(c.sheet)
                    .border(2.dp, c.ink),
            ) {
                Box(Modifier.fillMaxSize()) { page(item) }
                Flag(stringResource(R.string.hc_page_short, from + 1), Modifier.align(Alignment.TopStart).offset((-6).dp, (-10).dp))
            }
        }
    }
}

private class ReorderLabels(
    val earlier: String,
    val later: String,
    val start: String,
    val end: String,
    val position: String,
    val delete: String,
)

/**
 * The bottom bar while a page is lifted: an ink slip ("Moving page 4 → 2", announced politely) and, when the grid
 * allows deleting, a 120 dp Delete drop target outlined in accent.
 */
@Composable
fun PageReorderBar(state: PageReorderState, modifier: Modifier = Modifier) {
    val c = Heycam.colors
    val from = (state.draggingIndex ?: return) + 1
    val to = state.targetIndex?.plus(1)
    val visible = when {
        state.overDelete -> stringResource(R.string.hc_drop_to_delete, from)
        to == null || to == from -> stringResource(R.string.hc_moving_page_here, from)
        else -> stringResource(R.string.hc_moving_page, from, to)
    }
    val spoken = when {
        state.overDelete -> visible
        to == null || to == from -> visible
        else -> stringResource(R.string.hc_moving_page_spoken, from, to)
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(c.paper)
            .padding(horizontal = HeycamSize.gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = HeycamSize.buttonLarge)
                .background(c.ink)
                .padding(horizontal = 14.dp)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            CapsText(visible, Heycam.type.kicker, c.onInk, maxLines = 2)
        }
        if (state.canDelete) {
            val over = state.overDelete
            Row(
                Modifier
                    .width(120.dp)
                    .heightIn(min = HeycamSize.buttonLarge)
                    .onGloballyPositioned { state.deleteBounds = it.boundsInRoot() }
                    .background(if (over) c.accent else c.accentWash)
                    .border(HeycamSize.hair, c.accent)
                    .padding(4.dp)
                    .drawBehind {
                        val dash = 3.dp.toPx()
                        drawRect(
                            color = if (over) c.onInk else c.accent,
                            style = Stroke(HeycamSize.hair.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                        )
                    }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (over) c.onInk else c.accent
                Icon(HeycamIcons.Delete, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.hc_delete), style = Heycam.type.button, color = tint)
            }
        }
    }
}
