package heycam.feature.document.pages

import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Where a finished drag should go. */
internal sealed interface DropResult {
    data class Move(val from: Int, val to: Int) : DropResult
    data class Delete(val pageKey: Long) : DropResult
}

/**
 * Drag-to-reorder for the page grid (F6). The lifted page is drawn as an overlay that follows the finger; its slot
 * stays as a dashed gap and a red bar marks where it will land. Positions are grid item indices.
 */
@Stable
internal class ReorderState(val grid: LazyGridState) {
    /** Key (page id) of the lifted page, or null when nothing is being dragged. */
    var draggingKey by mutableStateOf<Long?>(null)
        private set
    var from by mutableIntStateOf(-1)
        private set
    var target by mutableIntStateOf(-1)
        private set
    var overDelete by mutableStateOf(false)
        private set

    /** Top-left of the lifted page in grid coordinates, and its size. */
    var liftedOffset by mutableStateOf(Offset.Zero)
        private set
    var liftedSize by mutableStateOf(Size.Zero)
        private set

    /** The Delete drop target's bounds, and the grid's top-left, both in root coordinates. */
    var deleteBounds: Rect? = null
    var gridOrigin: Offset = Offset.Zero

    private var pointer = Offset.Zero
    private var grab = Offset.Zero

    val isDragging: Boolean get() = draggingKey != null

    /** Lifts the page under [position] (grid coordinates). False when the press is not on a page. */
    fun start(position: Offset): Boolean {
        val item = itemAt(position) ?: return false
        val key = item.key as? Long ?: return false
        draggingKey = key
        from = item.index
        target = item.index
        pointer = position
        grab = position - item.offset.toOffset()
        liftedSize = item.size.toSize()
        liftedOffset = item.offset.toOffset()
        overDelete = false
        return true
    }

    fun drag(delta: Offset) {
        if (!isDragging) return
        pointer += delta
        update()
    }

    /** Re-reads what is under the finger, e.g. after the grid auto-scrolled. */
    fun update() {
        if (!isDragging) return
        liftedOffset = pointer - grab
        overDelete = deleteBounds?.contains(gridOrigin + pointer) == true
        if (!overDelete) itemAt(pointer)?.let { if (it.key is Long) target = it.index }
    }

    /** How fast to scroll while the finger is near the top or bottom edge; 0 elsewhere. */
    fun edgeScroll(edgePx: Float, maxStepPx: Float): Float {
        if (!isDragging || overDelete) return 0f
        val height = grid.layoutInfo.viewportSize.height.toFloat()
        val y = pointer.y
        return when {
            y < edgePx -> -maxStepPx * ((edgePx - y) / edgePx).coerceIn(0f, 1f)
            y > height - edgePx -> maxStepPx * ((y - (height - edgePx)) / edgePx).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    fun end(): DropResult? {
        val key = draggingKey ?: return null
        val result = when {
            overDelete -> DropResult.Delete(key)
            target >= 0 && target != from -> DropResult.Move(from, target)
            else -> null
        }
        reset()
        return result
    }

    fun cancel() = reset()

    private fun reset() {
        draggingKey = null
        from = -1
        target = -1
        overDelete = false
    }

    private fun itemAt(position: Offset): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        val o = it.offset
        position.x >= o.x && position.x < o.x + it.size.width && position.y >= o.y && position.y < o.y + it.size.height
    }
}

private fun IntOffset.toOffset() = Offset(x.toFloat(), y.toFloat())
private fun IntSize.toSize() = Size(width.toFloat(), height.toFloat())

/** Where a TalkBack "move" action sends page [from] of [count]; null when it cannot move that way. */
internal enum class MoveAction { EARLIER, LATER, START, END }

internal fun MoveAction.destination(from: Int, count: Int): Int? {
    val to = when (this) {
        MoveAction.EARLIER -> from - 1
        MoveAction.LATER -> from + 1
        MoveAction.START -> 0
        MoveAction.END -> count - 1
    }
    return to.takeIf { it in 0 until count && it != from }
}
