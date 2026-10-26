package heycam.feature.document.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import heycam.core.designsystem.Flag
import heycam.core.model.Page
import heycam.core.model.PageId
import heycam.core.model.TextStatus
import heycam.feature.document.R
import heycam.feature.document.ui.LoadBar
import heycam.feature.document.ui.PageThumb
import heycam.feature.document.ui.Paper
import heycam.feature.document.ui.Type
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** A4-ish frame every page sits in, so rows line up whatever the page's own shape. */
private const val FRAME_ASPECT = 1f / 1.414f

internal data class PageGridActions(
    val onOpen: (index: Int) -> Unit = {},
    val onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onMoveToPosition: (index: Int) -> Unit = {},
    val onRotate: (PageId) -> Unit = {},
    val onDelete: (PageId) -> Unit = {},
    /** Says what a TalkBack action did, since the page moving or turning is all there is to see. */
    val onAnnounce: (String) -> Unit = {},
)

/** Page grid (S8): 3 columns on phones, up to 6 on wide screens; long-press drag to reorder or drop on Delete. */
@Composable
internal fun PageGrid(
    pages: List<Page>,
    highlightIndex: Int?,
    reorder: ReorderState,
    actions: PageGridActions,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(actions)
    val density = LocalDensity.current
    val canDrag = pages.size > 1
    LaunchedEffect(reorder.isDragging) {
        if (!reorder.isDragging) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val step = with(density) { 14.dp.toPx() }
        while (isActive && reorder.isDragging) {
            val delta = reorder.edgeScroll(edge, step)
            if (delta != 0f) {
                reorder.grid.scrollBy(delta)
                reorder.update()
            }
            delay(16)
        }
    }
    BoxWithConstraints(modifier) {
        val columns = (maxWidth / 120.dp).toInt().coerceIn(3, 6)
        val thumbPx = with(density) { (maxWidth / columns).roundToPx() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = reorder.grid,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(GAP),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { reorder.gridOrigin = it.positionInRoot() }
                .pointerInput(canDrag) {
                    if (!canDrag) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { if (reorder.start(it)) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDrag = { change, amount ->
                            if (reorder.isDragging) {
                                change.consume()
                                reorder.drag(amount)
                            }
                        },
                        onDragEnd = {
                            when (val drop = reorder.end()) {
                                is DropResult.Move -> current.onMove(drop.from, drop.to)
                                is DropResult.Delete -> current.onDelete(PageId(drop.pageKey))
                                null -> Unit
                            }
                        },
                        onDragCancel = { reorder.cancel() },
                    )
                },
        ) {
            itemsIndexed(pages, key = { _, page -> page.id.value }) { index, page ->
                val marker = when {
                    !reorder.isDragging || reorder.overDelete || index != reorder.target || reorder.target == reorder.from -> Marker.NONE
                    reorder.target < reorder.from -> Marker.BEFORE
                    else -> Marker.AFTER
                }
                PageCell(
                    page = page,
                    index = index,
                    count = pages.size,
                    highlighted = index == highlightIndex,
                    lifted = reorder.draggingKey == page.id.value,
                    marker = marker,
                    thumbPx = thumbPx,
                    actions = actions,
                )
            }
        }
        val liftedKey = reorder.draggingKey
        val lifted = liftedKey?.let { key -> pages.firstOrNull { it.id.value == key } }
        if (lifted != null) LiftedPage(lifted, pages.indexOf(lifted), reorder, thumbPx)
    }
}

private enum class Marker { NONE, BEFORE, AFTER }

private val GAP = 12.dp

@Composable
private fun PageCell(
    page: Page,
    index: Int,
    count: Int,
    highlighted: Boolean,
    lifted: Boolean,
    marker: Marker,
    thumbPx: Int,
    actions: PageGridActions,
) {
    val accent = Paper.accent
    val rule = Paper.rule
    val description = stringResource(R.string.document_page_of, index + 1, count)
    val status = stringResource(page.textStatus.label)
    val moveLabels = MoveAction.entries.associateWith { stringResource(it.label) }
    val positionLabel = stringResource(R.string.document_move_to_position)
    val rotateLabel = stringResource(R.string.document_rotate)
    val deleteLabel = stringResource(R.string.document_delete_page)
    val rotated = stringResource(R.string.document_rotated)
    val resources = LocalResources.current
    Column(
        Modifier
            .clickable(onClickLabel = stringResource(R.string.document_open)) { actions.onOpen(index) }
            .semantics {
                contentDescription = description
                stateDescription = status
                customActions = buildList {
                    MoveAction.entries.forEach { action ->
                        action.destination(index, count)?.let { to ->
                            add(
                                CustomAccessibilityAction(moveLabels.getValue(action)) {
                                    actions.onMove(index, to)
                                    actions.onAnnounce(resources.getString(R.string.document_moved, to + 1))
                                    true
                                },
                            )
                        }
                    }
                    if (count > 1) add(CustomAccessibilityAction(positionLabel) { actions.onMoveToPosition(index); true })
                    add(CustomAccessibilityAction(rotateLabel) { actions.onRotate(page.id); actions.onAnnounce(rotated); true })
                    if (count > 1) add(CustomAccessibilityAction(deleteLabel) { actions.onDelete(page.id); true })
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val ink = Paper.ink
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(FRAME_ASPECT)
                .drawWithContent {
                    drawContent()
                    val bar = 3.dp.toPx()
                    val gap = GAP.toPx()
                    when (marker) {
                        Marker.BEFORE -> drawRect(accent, Offset(-(gap + bar) / 2f, 0f), Size(bar, size.height))
                        Marker.AFTER -> drawRect(accent, Offset(size.width + (gap - bar) / 2f, 0f), Size(bar, size.height))
                        Marker.NONE -> Unit
                    }
                },
        ) {
            if (lifted) {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(
                            ink,
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    },
                )
            } else {
                PageThumb(
                    path = page.thumbPath,
                    stamp = page.editsVersion,
                    targetPx = thumbPx,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (highlighted) Modifier.border(2.dp, ink).padding(3.dp) else Modifier)
                        .border(1.dp, rule),
                )
                if (page.textStatus == TextStatus.PENDING) {
                    Box(Modifier.align(Alignment.BottomCenter).clearAndSetSemantics {}) { LoadBar(progress = null) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.document_frame_no, index + 1),
            style = Type.frameNo,
            color = if (highlighted) Paper.accent else Paper.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** The page under the finger: tilted, lifted, outlined in ink, tagged with its number. */
@Composable
private fun LiftedPage(page: Page, index: Int, reorder: ReorderState, thumbPx: Int) {
    val density = LocalDensity.current
    val size = with(density) { reorder.liftedSize.width.toDp() }
    val offset = reorder.liftedOffset
    Box(
        Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .width(size)
            .aspectRatio(FRAME_ASPECT)
            .graphicsLayer {
                scaleX = 1.04f
                scaleY = 1.04f
                rotationZ = -3f
                shadowElevation = 12.dp.toPx()
            }
            .background(Paper.sheet)
            .border(2.dp, Paper.ink)
            .clearAndSetSemantics {},
    ) {
        PageThumb(page.thumbPath, page.editsVersion, thumbPx, Modifier.fillMaxSize())
        Flag(stringResource(R.string.document_frame_no, index + 1), Modifier.padding(4.dp))
    }
}

/** While dragging the bottom bar becomes an ink slip "Moving page 4 → 2" and a Delete drop target. */
@Composable
internal fun DragBar(reorder: ReorderState, modifier: Modifier = Modifier) {
    val moving = stringResource(R.string.document_moving, reorder.from + 1, reorder.target + 1)
    Row(modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .background(Paper.ink)
                .padding(horizontal = 14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(moving, style = Type.kicker, color = Paper.onInk, maxLines = 1)
        }
        val accent = Paper.accent
        val over = reorder.overDelete
        Row(
            Modifier
                .width(120.dp)
                .heightIn(min = 56.dp)
                .onGloballyPositioned { reorder.deleteBounds = it.boundsInRoot() }
                .background(if (over) Paper.accentWash else Paper.paper)
                .drawBehind {
                    drawRect(
                        accent,
                        style = Stroke(
                            if (over) 2.dp.toPx() else 1.dp.toPx(),
                            pathEffect = if (over) null else PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())),
                        ),
                    )
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.document_ic_delete), null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.document_delete), style = Type.button, color = accent)
        }
    }
}

internal val MoveAction.label: Int
    get() = when (this) {
        MoveAction.EARLIER -> R.string.document_move_earlier
        MoveAction.LATER -> R.string.document_move_later
        MoveAction.START -> R.string.document_move_to_start
        MoveAction.END -> R.string.document_move_to_end
    }

internal val TextStatus.label: Int
    get() = when (this) {
        TextStatus.PENDING -> R.string.document_text_reading
        TextStatus.DONE -> R.string.document_text_ready
        TextStatus.EMPTY -> R.string.document_text_none
        TextStatus.FAILED -> R.string.document_text_failed
        TextStatus.SKIPPED -> R.string.document_text_off
    }
