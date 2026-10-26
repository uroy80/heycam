package heycam.feature.scan.review

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import heycam.core.designsystem.HeycamMenu
import heycam.core.designsystem.ReportBottomBar
import heycam.core.model.DocumentId
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.StagedPage
import heycam.feature.scan.R
import heycam.feature.scan.entry.MAX_PICK
import heycam.feature.scan.entry.label
import heycam.feature.scan.ui.ArticleTopBar
import heycam.feature.scan.ui.BottomBarInsets
import heycam.feature.scan.ui.InkIconButton
import heycam.feature.scan.ui.InkTextButton
import heycam.feature.scan.ui.LoadBar
import heycam.feature.scan.ui.Paper
import heycam.feature.scan.ui.ScreenInsets
import heycam.feature.scan.ui.TopBarInsets
import heycam.feature.scan.ui.Type

/** Renders a page preview; the screen never touches the renderer directly so tests can pass a stub. */
internal typealias PreviewLoader = suspend (page: StagedPage, edits: PageEdits, maxLongEdgePx: Int) -> Bitmap?

internal data class ReviewCallbacks(
    val onSelect: (Int) -> Unit = {},
    val onFilter: (PageFilter) -> Unit = {},
    val onApplyToAll: (PageFilter) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onAddScan: () -> Unit = {},
    val onAddPhotos: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onSave: () -> Unit = {},
)

@Composable
internal fun ReviewDestination(
    onSaved: (documentId: DocumentId, newDocument: Boolean) -> Unit,
    onClosed: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val saved by rememberUpdatedState(onSaved)
    val closed by rememberUpdatedState(onClosed)
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onScannerResult(it.resultCode, it.data)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) {
        viewModel.onPhotosPicked(it)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ReviewEvent.Saved -> saved(event.documentId, event.newDocument)
                ReviewEvent.Closed -> closed()
                is ReviewEvent.LaunchScanner -> try {
                    scanner.launch(event.request)
                } catch (e: ActivityNotFoundException) {
                    viewModel.onLaunchFailed()
                }
                is ReviewEvent.Message -> snackbar.showSnackbar(
                    when (val m = event.message) {
                        ReviewMessage.Failed -> resources.getString(R.string.scan_failed)
                        ReviewMessage.ScannerNotReady -> resources.getString(R.string.scan_not_ready)
                        is ReviewMessage.ScannerUnavailable -> resources.getString(m.reason.label)
                        is ReviewMessage.Rejected -> resources.getQuantityString(R.plurals.scan_rejected, m.count, m.count)
                    },
                )
            }
        }
    }
    ReviewScreen(
        state = state,
        preview = viewModel::preview,
        snackbar = snackbar,
        callbacks = ReviewCallbacks(
            onSelect = viewModel::select,
            onFilter = viewModel::setFilter,
            onApplyToAll = viewModel::applyToAll,
            onRotate = viewModel::rotate,
            onDelete = viewModel::deleteCurrent,
            onAddScan = { activity?.let(viewModel::addFromScanner) ?: viewModel.onLaunchFailed() },
            onAddPhotos = {
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    viewModel.onPickerOpened()
                } catch (e: ActivityNotFoundException) {
                    viewModel.onLaunchFailed()
                }
            },
            onDiscard = viewModel::discard,
            onSave = viewModel::save,
        ),
    )
}

/** Below this window height (a phone on its side) the controls move beside the page instead of under it. */
private val CompactHeight = 480.dp

/** Above this font scale the two action buttons stack. */
private const val LARGE_FONT_SCALE = 1.3f

/** Review (S7). */
@Composable
internal fun ReviewScreen(
    state: ReviewUiState,
    preview: PreviewLoader,
    snackbar: SnackbarHostState,
    callbacks: ReviewCallbacks,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var showTip by rememberSaveable { mutableStateOf(false) }
    val pages = state.pages
    val current = state.currentPage
    val idle = state.busy == ReviewBusy.IDLE
    // Back always stays on this screen: while saving it does nothing, so the save is never cut short; otherwise it
    // asks before discarding.
    BackHandler { if (state.busy != ReviewBusy.SAVING) confirmDiscard = true }
    val column = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxHeight < CompactHeight
        val sideWidth = (maxWidth * 0.4f).coerceIn(280.dp, 340.dp)
        Scaffold(
            containerColor = Paper.canvas,
            contentWindowInsets = ScreenInsets,
            topBar = {
                ArticleTopBar(
                    title = stringResource(R.string.scan_review),
                    kicker = if (pages.isNotEmpty()) stringResource(R.string.scan_page_of, state.current + 1, pages.size) else null,
                    navigationIcon = R.drawable.scan_ic_arrow_back,
                    navigationLabel = stringResource(R.string.scan_back),
                    onNavigate = { if (state.busy != ReviewBusy.SAVING) confirmDiscard = true },
                    modifier = Modifier.background(Paper.paper).windowInsetsPadding(TopBarInsets),
                ) {
                    if (state.session?.hasPhotos == true) {
                        InkIconButton(R.drawable.scan_ic_info, stringResource(R.string.scan_tip), { showTip = true })
                    }
                    InkIconButton(R.drawable.scan_ic_rotate, stringResource(R.string.scan_rotate), callbacks.onRotate, enabled = current != null && idle)
                    if (state.canDelete) {
                        InkIconButton(R.drawable.scan_ic_delete, stringResource(R.string.scan_delete_page), callbacks.onDelete, enabled = idle)
                    }
                }
            },
            bottomBar = {
                if (!sideBySide) {
                    // Its height reaches the app shell, so a scan message prints above the controls.
                    ReportBottomBar {
                        Column(Modifier.background(Paper.paper).windowInsetsPadding(BottomBarInsets)) {
                            ReviewControls(state, preview, callbacks, column)
                        }
                    }
                }
            },
            snackbarHost = {
                SnackbarHost(snackbar) { Snackbar(it, shape = RectangleShape, containerColor = Paper.ink, contentColor = Paper.onInk) }
            },
        ) { inner ->
            if (sideBySide) {
                Row(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (pages.isNotEmpty()) PagePager(state, preview, callbacks.onSelect)
                    }
                    VerticalDivider(thickness = 1.dp, color = Paper.rule)
                    Column(Modifier.width(sideWidth).fillMaxHeight().background(Paper.paper).verticalScroll(rememberScrollState())) {
                        ReviewControls(state, preview, callbacks, Modifier.fillMaxWidth())
                    }
                }
            } else {
                Box(Modifier.padding(inner).fillMaxSize()) {
                    if (pages.isNotEmpty()) PagePager(state, preview, callbacks.onSelect)
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            shape = RectangleShape,
            containerColor = Paper.paper,
            title = { Text(pluralStringResource(R.plurals.scan_discard_title, pages.size, pages.size), style = Type.title, color = Paper.ink) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; callbacks.onDiscard() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.scan_discard), color = Paper.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.scan_keep))
                }
            },
        )
    }
    if (showTip) {
        AlertDialog(
            onDismissRequest = { showTip = false },
            shape = RectangleShape,
            containerColor = Paper.paper,
            text = { Text(stringResource(R.string.scan_photo_tip), style = Type.body, color = Paper.ink) },
            confirmButton = {
                TextButton(onClick = { showTip = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.scan_ok)) }
            },
        )
    }
}

/**
 * The filter strip, "use on all pages", Add page and Save: under the page, or beside it in a short window. [modifier]
 * goes on each row, keeping them to the content column.
 */
@Composable
private fun ReviewControls(state: ReviewUiState, preview: PreviewLoader, callbacks: ReviewCallbacks, modifier: Modifier) {
    val current = state.currentPage
    HorizontalDivider(thickness = 1.dp, color = Paper.rule)
    if (state.busy == ReviewBusy.IDLE) Spacer(Modifier.height(2.dp)) else LoadBar(progress = null)
    if (current != null) {
        FilterStrip(current, preview, enabled = state.busy == ReviewBusy.IDLE, onFilter = callbacks.onFilter, modifier = modifier)
        ApplyRow(state, current, callbacks.onApplyToAll, modifier)
    }
    ActionRow(state, callbacks, modifier)
}

@Composable
private fun PagePager(state: ReviewUiState, preview: PreviewLoader, onSelect: (Int) -> Unit) {
    val pages = state.pages
    val pager = rememberPagerState(initialPage = state.current) { pages.size }
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { select(it) }
    }
    LaunchedEffect(state.current, pages.size) {
        if (state.current in pages.indices && pager.currentPage != state.current) pager.scrollToPage(state.current)
    }
    val previousLabel = stringResource(R.string.scan_previous_page)
    val nextLabel = stringResource(R.string.scan_next_page)
    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        pageSpacing = 16.dp,
        key = { pages.getOrNull(it)?.image?.file?.path ?: it },
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        val page = pages.getOrNull(index) ?: return@HorizontalPager
        val description = stringResource(R.string.scan_page_of, index + 1, pages.size) + ", " + stringResource(page.edits.filter.label)
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = description
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(previousLabel) { select(index - 1); true })
                        if (index < pages.lastIndex) add(CustomAccessibilityAction(nextLabel) { select(index + 1); true })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            PagePreview(page, page.edits, PAGE_PREVIEW_PX, preview, Modifier.fillMaxSize())
        }
    }
}

/** The page scaled to fit its box (up as well as down), with a hairline border; a LoadBar while it renders. */
@Composable
private fun PagePreview(page: StagedPage, edits: PageEdits, maxPx: Int, preview: PreviewLoader, modifier: Modifier = Modifier) {
    val loader by rememberUpdatedState(preview)
    val image by produceState<ImageBitmap?>(null, page.image.file.path, edits, maxPx) {
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

/** Original · Auto · Grey · B&W, each a live preview of the current page. */
@Composable
private fun FilterStrip(
    page: StagedPage,
    preview: PreviewLoader,
    enabled: Boolean,
    onFilter: (PageFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbPx = with(LocalDensity.current) { FILTER_THUMB_HEIGHT.roundToPx() }
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        // Each choice is as wide as its name needs (never narrower than its picture), so names never run together.
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReviewFilters.forEach { filter ->
            val selected = page.edits.filter == filter
            val label = stringResource(filter.label)
            Column(
                Modifier
                    .widthIn(min = 76.dp)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onFilter(filter) }),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .clearAndSetSemantics {}
                        .padding(4.dp)
                        .then(if (selected) Modifier.border(2.dp, Paper.ink).padding(4.dp) else Modifier.padding(4.dp)),
                ) {
                    PagePreview(page, page.edits.copy(filter = filter), thumbPx, preview, Modifier.size(56.dp, FILTER_THUMB_HEIGHT))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(painterResource(R.drawable.scan_ic_check), null, tint = Paper.accent, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                    }
                    Text(
                        label.toUpperCase(Locale.current),
                        style = Type.modeLabel,
                        color = if (selected) Paper.accent else Paper.ink,
                        maxLines = 1,
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}

/** "Use Auto on all 3 pages" and the page's pixel size; the link wraps rather than pushing the size off. */
@Composable
private fun ApplyRow(state: ReviewUiState, current: StagedPage, onApplyToAll: (PageFilter) -> Unit, modifier: Modifier = Modifier) {
    val filter = current.edits.filter
    val showApply = state.pages.size > 1 && state.pages.any { it.edits.filter != filter }
    val (w, h) = current.displaySize()
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (showApply && state.busy == ReviewBusy.IDLE) {
                InkTextButton(
                    pluralStringResource(R.plurals.scan_apply_all, state.pages.size, stringResource(filter.label), state.pages.size),
                    { onApplyToAll(filter) },
                )
            }
        }
        Text(stringResource(R.string.scan_dimensions, w, h), style = Type.figure, color = Paper.muted, maxLines = 1)
    }
}

/** Add page (Scan or Photos) and Save, side by side or, at large font sizes and in narrow columns, stacked. */
@Composable
private fun ActionRow(state: ReviewUiState, callbacks: ReviewCallbacks, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = LocalDensity.current.fontScale > LARGE_FONT_SCALE || maxWidth < 360.dp
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.canAdd) AddPageButton(state, callbacks, fillWidth = true)
                SaveButton(state, callbacks, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.canAdd) AddPageButton(state, callbacks, fillWidth = false)
                SaveButton(state, callbacks, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AddPageButton(state: ReviewUiState, callbacks: ReviewCallbacks, fillWidth: Boolean) {
    val idle = state.busy == ReviewBusy.IDLE
    var addOpen by rememberSaveable { mutableStateOf(false) }
    val width = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    Box(width) {
        OutlinedButton(
            onClick = { addOpen = true },
            enabled = idle,
            shape = RoundedCornerShape(2.dp),
            border = BorderStroke(1.dp, if (idle) Paper.ink else Paper.rule),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper.ink),
            modifier = width.heightIn(min = 56.dp),
        ) {
            Icon(painterResource(R.drawable.scan_ic_add), null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scan_add_page), style = Type.button, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HeycamMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scan_add_scan)) },
                leadingIcon = { Icon(painterResource(R.drawable.scan_ic_camera), null, tint = Paper.ink) },
                onClick = { addOpen = false; callbacks.onAddScan() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scan_add_photos)) },
                leadingIcon = { Icon(painterResource(R.drawable.scan_ic_image), null, tint = Paper.ink) },
                onClick = { addOpen = false; callbacks.onAddPhotos() },
            )
        }
    }
}

@Composable
private fun SaveButton(state: ReviewUiState, callbacks: ReviewCallbacks, modifier: Modifier = Modifier) {
    Button(
        onClick = callbacks.onSave,
        enabled = state.busy == ReviewBusy.IDLE && state.pages.isNotEmpty(),
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Paper.ink, contentColor = Paper.onInk),
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Text(
            if (state.replacing) stringResource(R.string.scan_replace_page)
            else pluralStringResource(R.plurals.scan_save_pages, state.pages.size, state.pages.size),
            style = Type.button.copy(fontSize = 16.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal val PageFilter.label: Int
    get() = when (this) {
        PageFilter.ORIGINAL -> R.string.scan_filter_original
        PageFilter.AUTO -> R.string.scan_filter_auto
        PageFilter.GRAYSCALE -> R.string.scan_filter_grey
        PageFilter.BLACK_WHITE -> R.string.scan_filter_bw
        PageFilter.WHITEBOARD -> R.string.scan_filter_whiteboard
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

private const val PAGE_PREVIEW_PX = 1600
private val FILTER_THUMB_HEIGHT: Dp = 76.dp
