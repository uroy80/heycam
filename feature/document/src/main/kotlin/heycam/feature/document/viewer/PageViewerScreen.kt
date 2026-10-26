package heycam.feature.document.viewer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.feature.document.DocumentMessage
import heycam.feature.document.R
import heycam.feature.document.rememberUndoWindowMs
import heycam.feature.document.ui.ArticleTopBar
import heycam.feature.document.ui.BottomBarInsets
import heycam.feature.document.ui.InkSnackbarHost
import heycam.feature.document.ui.InkTextButton
import heycam.feature.document.ui.LabelledIcon
import heycam.feature.document.ui.LoadBar
import heycam.feature.document.ui.Paper
import heycam.feature.document.ui.ScreenInsets
import heycam.feature.document.ui.TopBarInsets
import heycam.feature.document.ui.Type
import kotlinx.coroutines.withTimeoutOrNull

/** Renders a page preview; the screen never touches the renderer directly so tests can pass a stub. */
internal typealias PagePreviewLoader = suspend (page: Page, edits: PageEdits, maxLongEdgePx: Int) -> Bitmap?

/** The filters offered in MVP, in strip order (Whiteboard arrives in v1.1). */
internal val ViewerFilters = listOf(PageFilter.ORIGINAL, PageFilter.AUTO, PageFilter.GRAYSCALE, PageFilter.BLACK_WHITE)

internal data class ViewerCallbacks(
    val onBack: () -> Unit = {},
    val onSelect: (Int) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onFilter: (PageFilter) -> Unit = {},
    val onFilterAll: (PageFilter) -> Unit = {},
    val onRetake: (Page) -> Unit = {},
    val onDelete: () -> Unit = {},
)

@Composable
internal fun PageViewerDestination(
    onBack: () -> Unit,
    onRetake: (pageId: Long) -> Unit,
    viewModel: PageViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val back by rememberUpdatedState(onBack)
    val undoWindow = rememberUndoWindowMs()
    LaunchedEffect(state.missing) { if (state.missing) back() }
    LaunchedEffect(viewModel, undoWindow) {
        viewModel.messages.collect { message ->
            if (message is DocumentMessage.PageDeleted) {
                val result = withTimeoutOrNull(undoWindow) {
                    snackbar.showSnackbar(
                        resources.getString(R.string.document_page_deleted),
                        actionLabel = resources.getString(R.string.document_undo),
                        duration = SnackbarDuration.Indefinite,
                    )
                }
                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(message.pageId)
            } else {
                snackbar.showSnackbar(resources.getString(R.string.document_failed))
            }
        }
    }
    PageViewerScreen(
        state = state,
        preview = viewModel::preview,
        snackbar = snackbar,
        callbacks = ViewerCallbacks(
            onBack = onBack,
            onSelect = viewModel::select,
            onRotate = viewModel::rotate,
            onFilter = viewModel::setFilter,
            onFilterAll = viewModel::applyFilterToAll,
            onRetake = { onRetake(it.id.value) },
            onDelete = { viewModel.deleteCurrent(undoWindow) },
        ),
    )
}

/**
 * The largest box with [image]'s shape that fits in [maxWidth] x [maxHeight]: matching the height first when the
 * space is wider than the picture, the width first otherwise.
 */
internal fun Modifier.fitInside(image: ImageBitmap, maxWidth: Dp, maxHeight: Dp): Modifier {
    if (image.width <= 0 || image.height <= 0) return this
    val aspect = image.width.toFloat() / image.height
    val boundedHeight = maxHeight != Dp.Infinity && maxHeight > 0.dp
    return aspectRatio(aspect, matchHeightConstraintsFirst = boundedHeight && maxWidth / maxHeight > aspect)
}

/** Page viewer (S9): the page full-bleed on canvas; Rotate, Filter, Retake, Delete underneath. */
@Composable
internal fun PageViewerScreen(
    state: ViewerUiState,
    preview: PagePreviewLoader,
    snackbar: SnackbarHostState,
    callbacks: ViewerCallbacks,
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val page = state.currentPage
    val column = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)
    BackHandler(enabled = filtersOpen) { filtersOpen = false }
    val shown = stringResource(R.string.document_filters_shown)
    val hidden = stringResource(R.string.document_filters_hidden)
    Scaffold(
        containerColor = Paper.canvas,
        contentWindowInsets = ScreenInsets,
        topBar = {
            ArticleTopBar(
                title = if (state.pages.isEmpty()) "" else stringResource(R.string.document_page_of, state.current + 1, state.pages.size),
                kicker = state.document?.title,
                navigationIcon = R.drawable.document_ic_arrow_back,
                navigationLabel = stringResource(R.string.document_back),
                onNavigate = callbacks.onBack,
                modifier = Modifier.background(Paper.paper).windowInsetsPadding(TopBarInsets),
            )
        },
        bottomBar = {
            Column(Modifier.background(Paper.paper).windowInsetsPadding(BottomBarInsets)) {
                HorizontalDivider(thickness = 1.dp, color = Paper.ink)
                if (filtersOpen && page != null) {
                    FilterStrip(page, preview, callbacks.onFilter, column)
                    if (state.pages.size > 1) {
                        Row(column.fillMaxWidth().padding(start = 4.dp)) {
                            InkTextButton(
                                pluralStringResource(R.plurals.document_filter_all, state.pages.size, stringResource(page.edits.filter.label), state.pages.size),
                                { callbacks.onFilterAll(page.edits.filter) },
                            )
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = Paper.rule)
                }
                Row(column.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    LabelledIcon(R.drawable.document_ic_rotate, stringResource(R.string.document_rotate), callbacks.onRotate, enabled = page != null)
                    LabelledIcon(
                        R.drawable.document_ic_filter,
                        stringResource(R.string.document_filter),
                        { filtersOpen = !filtersOpen },
                        // The strip it opens is announced as shown or hidden, with expand / collapse for switch access.
                        Modifier.semantics {
                            stateDescription = if (filtersOpen) shown else hidden
                            if (filtersOpen) collapse { filtersOpen = false; true } else expand { filtersOpen = true; true }
                        },
                        enabled = page != null,
                    )
                    LabelledIcon(R.drawable.document_ic_camera, stringResource(R.string.document_retake), { page?.let(callbacks.onRetake) }, enabled = page != null)
                    if (state.canDelete) {
                        LabelledIcon(R.drawable.document_ic_delete, stringResource(R.string.document_delete), callbacks.onDelete, destructive = true)
                    }
                }
            }
        },
        snackbarHost = { InkSnackbarHost(snackbar) },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            if (state.pages.isNotEmpty()) Pager(state, preview, callbacks.onSelect)
        }
    }
}

@Composable
private fun Pager(state: ViewerUiState, preview: PagePreviewLoader, onSelect: (Int) -> Unit) {
    val pages = state.pages
    val pager = rememberPagerState(initialPage = state.current) { pages.size }
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { select(it) } }
    LaunchedEffect(state.current, pages.size) {
        if (state.current in pages.indices && pager.currentPage != state.current) pager.scrollToPage(state.current)
    }
    val previousLabel = stringResource(R.string.document_previous_page)
    val nextLabel = stringResource(R.string.document_next_page)
    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(16.dp),
        pageSpacing = 16.dp,
        key = { pages.getOrNull(it)?.id?.value ?: it },
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        val page = pages.getOrNull(index) ?: return@HorizontalPager
        val description = stringResource(R.string.document_page_of, index + 1, pages.size) + ", " + stringResource(page.edits.filter.label)
        PagePicture(
            page,
            page.edits,
            PAGE_PREVIEW_PX,
            preview,
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = description
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(previousLabel) { select(index - 1); true })
                        if (index < pages.lastIndex) add(CustomAccessibilityAction(nextLabel) { select(index + 1); true })
                    }
                },
        )
    }
}

@Composable
private fun PagePicture(page: Page, edits: PageEdits, maxPx: Int, preview: PagePreviewLoader, modifier: Modifier = Modifier) {
    val loader by rememberUpdatedState(preview)
    val image by produceState<ImageBitmap?>(null, page.source.path, edits, maxPx) {
        value = loader(page, edits, maxPx)?.asImageBitmap()
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap == null) {
            LoadBar(progress = null, modifier = Modifier.widthIn(max = 120.dp))
        } else {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fitInside(bitmap, maxWidth, maxHeight).border(1.dp, Paper.line),
            )
        }
    }
}

/** Original · Auto · Grey · B&W previews of the current page. */
@Composable
private fun FilterStrip(page: Page, preview: PagePreviewLoader, onFilter: (PageFilter) -> Unit, modifier: Modifier = Modifier) {
    val thumbPx = with(LocalDensity.current) { 76.dp.roundToPx() }
    Row(
        modifier.horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 12.dp, vertical = 8.dp),
        // Each choice is as wide as its name needs (never narrower than its picture), so names never run together.
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ViewerFilters.forEach { filter ->
            val selected = page.edits.filter == filter
            val label = stringResource(filter.label)
            Column(
                Modifier.widthIn(min = 76.dp).selectable(selected = selected, role = Role.RadioButton, onClick = { onFilter(filter) }),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .clearAndSetSemantics {}
                        .padding(4.dp)
                        .then(if (selected) Modifier.border(2.dp, Paper.ink).padding(4.dp) else Modifier.padding(4.dp)),
                ) {
                    PagePicture(page, page.edits.copy(filter = filter), thumbPx, preview, Modifier.size(56.dp, 76.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(painterResource(R.drawable.document_ic_check), null, tint = Paper.accent, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                    }
                    Text(
                        label.toUpperCase(Locale.current),
                        style = Type.kicker,
                        color = if (selected) Paper.accent else Paper.ink,
                        maxLines = 1,
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}

internal val PageFilter.label: Int
    get() = when (this) {
        PageFilter.ORIGINAL -> R.string.document_filter_original
        PageFilter.AUTO -> R.string.document_filter_auto
        PageFilter.GRAYSCALE -> R.string.document_filter_grey
        PageFilter.BLACK_WHITE -> R.string.document_filter_bw
        PageFilter.WHITEBOARD -> R.string.document_filter_whiteboard
    }

private const val PAGE_PREVIEW_PX = 1600
