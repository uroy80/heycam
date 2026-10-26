package heycam.feature.document

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import heycam.core.designsystem.Flag
import heycam.core.designsystem.HeycamMenu
import heycam.core.designsystem.Kicker
import heycam.core.designsystem.ReportBottomBar
import heycam.core.export.ExportFailure
import heycam.core.export.TextPendingPolicy
import heycam.core.model.Folder
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.PageId
import heycam.feature.document.pages.DragBar
import heycam.feature.document.pages.PageGrid
import heycam.feature.document.pages.PageGridActions
import heycam.feature.document.pages.ReorderState
import heycam.feature.document.text.TextEdit
import heycam.feature.document.text.TextPane
import heycam.feature.document.text.TextPaneActions
import heycam.feature.document.ui.Announcement
import heycam.feature.document.ui.Announcer
import heycam.feature.document.ui.ArticleTopBar
import heycam.feature.document.ui.BottomBarInsets
import heycam.feature.document.ui.ConfirmDialog
import heycam.feature.document.ui.EndOrBelow
import heycam.feature.document.ui.InkIconButton
import heycam.feature.document.ui.InkSnackbarHost
import heycam.feature.document.ui.InkTextButton
import heycam.feature.document.ui.LabelledIcon
import heycam.feature.document.ui.LoadBar
import heycam.feature.document.ui.Paper
import heycam.feature.document.ui.ScreenInsets
import heycam.feature.document.ui.TopBarInsets
import heycam.feature.document.ui.Type
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.withTimeoutOrNull

/** Everything the document screen can ask for. Defaults are no-ops so tests pass only what they need. */
internal data class DocumentCallbacks(
    val onBack: () -> Unit = {},
    val onTab: (DocumentTab) -> Unit = {},
    val onOpenPage: (Int) -> Unit = {},
    val onScanMore: () -> Unit = {},
    val onImportMore: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onTogglePinned: () -> Unit = {},
    val onMoveTo: (Folder?) -> Unit = {},
    val loadFolderTree: suspend () -> List<FolderNode> = { emptyList() },
    val onTrash: () -> Unit = {},
    val onMovePage: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onRotate: (PageId) -> Unit = {},
    val onDeletePage: (PageId) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onNextMatch: () -> Unit = {},
    val onPreviousMatch: () -> Unit = {},
    val onRetryText: (PageId) -> Unit = {},
    val onSaveText: (PageId, String) -> Unit = { _, _ -> },
    val onReadAgain: (OcrScript?) -> Unit = {},
    val onShareText: (TextPendingPolicy) -> Unit = {},
    val onCopyAll: () -> Unit = {},
    val onRetryModels: () -> Unit = {},
)

/**
 * How long a page-delete Undo stays on offer: [PageDeletions.UNDO_WINDOW_MS], stretched to the user's accessibility
 * "time to take action" setting.
 */
@Composable
internal fun rememberUndoWindowMs(): Long {
    val accessibility = LocalAccessibilityManager.current
    return remember(accessibility) {
        accessibility?.calculateRecommendedTimeoutMillis(
            PageDeletions.UNDO_WINDOW_MS,
            containsIcons = false,
            containsText = true,
            containsControls = true,
        ) ?: PageDeletions.UNDO_WINDOW_MS
    }
}

@Composable
internal fun DocumentDestination(
    onBack: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onScanMore: () -> Unit,
    onImportMore: () -> Unit,
    onExport: () -> Unit,
    viewModel: DocumentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val find by viewModel.find.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var pendingShare by rememberSaveable { mutableStateOf<Int?>(null) }
    val back by rememberUpdatedState(onBack)
    val undoWindow = rememberUndoWindowMs()

    LaunchedEffect(state.missing) { if (state.missing) back() }
    LaunchedEffect(viewModel, undoWindow) {
        viewModel.events.collect { event ->
            when (event) {
                is DocumentEvent.Share -> try {
                    context.startActivity(event.chooser)
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar(resources.getString(R.string.document_share_failed))
                }
                is DocumentEvent.AskTextPending -> pendingShare = event.pages
                is DocumentEvent.Message -> when (val m = event.message) {
                    is DocumentMessage.PageDeleted -> {
                        val result = withTimeoutOrNull(undoWindow) {
                            snackbar.showSnackbar(
                                resources.getString(R.string.document_page_deleted),
                                actionLabel = resources.getString(R.string.document_undo),
                                duration = SnackbarDuration.Indefinite,
                            )
                        }
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(m.pageId)
                    }
                    else -> snackbar.showSnackbar(m.text(resources))
                }
            }
        }
    }

    DocumentScreen(
        state = state,
        tab = tab,
        find = find,
        initialPage = viewModel.initialPage,
        snackbar = snackbar,
        callbacks = DocumentCallbacks(
            onBack = onBack,
            onTab = viewModel::setTab,
            onOpenPage = onOpenPage,
            onScanMore = onScanMore,
            onImportMore = onImportMore,
            onExport = onExport,
            onRename = viewModel::rename,
            onTogglePinned = viewModel::togglePinned,
            onMoveTo = viewModel::moveTo,
            loadFolderTree = viewModel::folderTree,
            onTrash = viewModel::trash,
            onMovePage = viewModel::movePage,
            onRotate = viewModel::rotate,
            onDeletePage = { viewModel.deletePage(it, undoWindow) },
            onQuery = viewModel::setQuery,
            onNextMatch = viewModel::nextMatch,
            onPreviousMatch = viewModel::previousMatch,
            onRetryText = viewModel::retryText,
            onSaveText = viewModel::saveText,
            onReadAgain = viewModel::readAgain,
            onShareText = viewModel::shareText,
            onCopyAll = {
                val text = state.pages.mapNotNull { state.texts[it.id]?.text?.takeIf(String::isNotBlank) }.joinToString("\n\n")
                if (text.isNotEmpty()) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(sensitiveClip(state.document?.title.orEmpty(), text))
                    // Android 13+ shows its own confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) viewModel.notifyCopied()
                }
            },
            onRetryModels = viewModel::retryModels,
        ),
    )

    pendingShare?.let { pages ->
        TextPendingDialog(
            pages = pages,
            onWait = { pendingShare = null; viewModel.shareText(TextPendingPolicy.WAIT) },
            onShareNow = { pendingShare = null; viewModel.shareText(TextPendingPolicy.WITHOUT_TEXT) },
            onDismiss = { pendingShare = null },
        )
    }
}

/**
 * Copied text marked sensitive: scans are often IDs, statements and letters, so Android 13+ hides it in the
 * clipboard preview and keyboards do not offer it as a suggestion. Pasting is unaffected.
 */
internal fun sensitiveClip(label: String, text: String): ClipData = ClipData.newPlainText(label, text).apply {
    description.extras = PersistableBundle().apply { putBoolean(IS_SENSITIVE, true) }
}

/** The platform constant from Android 13; earlier releases read the same key when they look at it at all. */
private val IS_SENSITIVE =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"

private fun DocumentMessage.text(resources: android.content.res.Resources): String = when (this) {
    is DocumentMessage.PageDeleted -> resources.getString(R.string.document_page_deleted)
    is DocumentMessage.Moved -> resources.getString(R.string.document_moved_to, folderName ?: resources.getString(R.string.document_contents))
    DocumentMessage.TextSaved -> resources.getString(R.string.document_text_saved)
    DocumentMessage.Copied -> resources.getString(R.string.document_copied)
    is DocumentMessage.ExportFailed ->
        resources.getString(if (reason == ExportFailure.NO_SPACE) R.string.document_no_space else R.string.document_share_failed)
    DocumentMessage.Failed -> resources.getString(R.string.document_failed)
}

/** Below this window height (a phone on its side) the header and actions move to a rail beside the pages. */
private val CompactHeight = 480.dp

/** Above this font scale the bottom-bar actions stack. */
private const val LARGE_FONT_SCALE = 1.3f

/** Document (S8) with its Pages | Text toggle (S10). */
@Composable
internal fun DocumentScreen(
    state: DocumentUiState,
    tab: DocumentTab,
    find: FindState,
    initialPage: Int?,
    snackbar: SnackbarHostState,
    callbacks: DocumentCallbacks,
) {
    val doc = state.document
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = initialPage ?: 0)
    val reorder = remember(grid) { ReorderState(grid) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialPage ?: 0)
    val announcer = remember { Announcer() }
    var findOpen by rememberSaveable { mutableStateOf(find.query.isNotEmpty()) }
    var editPage by rememberSaveable { mutableStateOf<Long?>(null) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var askDiscardEdit by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }
    var trashing by rememberSaveable { mutableStateOf(false) }
    var choosingScript by rememberSaveable { mutableStateOf(false) }
    var positionFor by rememberSaveable { mutableStateOf<Int?>(null) }
    val column = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 840.dp)
    val editIndex = editPage?.let { id -> state.pages.indexOfFirst { it.id.value == id } }?.takeIf { it >= 0 }
    val edit = editPage?.let { id -> TextEdit(PageId(id), draft) }
    val editOriginal = editPage?.let { state.texts[PageId(it)]?.text }.orEmpty()

    fun startEdit(pageId: PageId) {
        val text = state.texts[pageId]?.text.orEmpty()
        draft = TextFieldValue(text, TextRange(text.length))
        editPage = pageId.value
    }
    fun saveEdit() {
        editPage?.let { callbacks.onSaveText(PageId(it), draft.text) }
        editPage = null
    }
    // Leaving an edit with changes asks first; Back and the close button both come here.
    fun leaveEdit() {
        if (draft.text != editOriginal) askDiscardEdit = true else editPage = null
    }

    BackHandler(enabled = editPage != null) { leaveEdit() }
    BackHandler(enabled = editPage == null && findOpen && tab == DocumentTab.TEXT) {
        findOpen = false
        callbacks.onQuery("")
    }
    // The page went away (deleted, or the document emptied elsewhere): nothing left to edit.
    LaunchedEffect(editPage, editIndex, state.loaded) {
        if (editPage != null && state.loaded && editIndex == null) editPage = null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxHeight < CompactHeight && editPage == null
        val railWidth = (maxWidth * 0.36f).coerceIn(280.dp, 340.dp)
        val actions: @Composable (Modifier) -> Unit = { modifier ->
            DocumentActions(state, tab, reorder, callbacks, onLanguage = { choosingScript = true }, modifier = modifier)
        }
        Scaffold(
            containerColor = Paper.paper,
            contentWindowInsets = ScreenInsets,
            topBar = {
                val bar = Modifier.background(Paper.paper).windowInsetsPadding(TopBarInsets)
                if (editPage != null) {
                    ArticleTopBar(
                        title = stringResource(R.string.document_editing_page, (editIndex ?: 0) + 1),
                        navigationIcon = R.drawable.document_ic_close,
                        navigationLabel = stringResource(R.string.document_cancel),
                        onNavigate = ::leaveEdit,
                        modifier = bar,
                    ) {
                        InkTextButton(stringResource(R.string.document_save), ::saveEdit, Modifier.padding(end = 4.dp))
                    }
                } else {
                    ArticleTopBar(
                        title = doc?.title.orEmpty(),
                        navigationIcon = R.drawable.document_ic_arrow_back,
                        navigationLabel = stringResource(R.string.document_back),
                        onNavigate = callbacks.onBack,
                        modifier = bar,
                    ) {
                        InkIconButton(R.drawable.document_ic_search, stringResource(R.string.document_find), {
                            callbacks.onTab(DocumentTab.TEXT)
                            findOpen = true
                        })
                        DocumentMenu(
                            pinned = doc?.pinned == true,
                            onRename = { renaming = true },
                            onMove = { moving = true },
                            onTogglePinned = callbacks.onTogglePinned,
                            onExport = callbacks.onExport,
                            onReadAgain = { choosingScript = true },
                            onTrash = { trashing = true },
                        )
                    }
                }
            },
            bottomBar = {
                // While a page is being corrected the bar steps aside, so the keyboard leaves the field more room.
                if (editPage == null && !sideBySide) {
                    // Its height reaches the app shell, so a scan message prints above the bar, not over it.
                    ReportBottomBar {
                        Column(Modifier.background(Paper.paper).windowInsetsPadding(BottomBarInsets)) {
                            HorizontalDivider(thickness = 1.dp, color = Paper.ink)
                            actions(column)
                        }
                    }
                }
            },
            snackbarHost = { InkSnackbarHost(snackbar) },
        ) { inner ->
            // The bars are already padded for, so the keyboard only takes what it covers beyond them.
            Box(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().imePadding()) {
                if (doc != null) {
                    val pane: @Composable (Modifier) -> Unit = { modifier ->
                        when (tab) {
                            DocumentTab.PAGES -> PageGrid(
                                pages = state.pages,
                                highlightIndex = initialPage,
                                reorder = reorder,
                                actions = PageGridActions(
                                    onOpen = callbacks.onOpenPage,
                                    onMove = callbacks.onMovePage,
                                    onMoveToPosition = { positionFor = it },
                                    onRotate = callbacks.onRotate,
                                    onDelete = callbacks.onDeletePage,
                                    onAnnounce = announcer::say,
                                ),
                                modifier = modifier,
                            )
                            DocumentTab.TEXT -> TextPane(
                                pages = state.pages,
                                texts = state.texts,
                                find = find,
                                findOpen = findOpen,
                                edit = edit,
                                list = list,
                                actions = TextPaneActions(
                                    onQuery = callbacks.onQuery,
                                    onNext = callbacks.onNextMatch,
                                    onPrevious = callbacks.onPreviousMatch,
                                    onCloseFind = { findOpen = false; callbacks.onQuery("") },
                                    onRetry = callbacks.onRetryText,
                                    onEdit = ::startEdit,
                                    onDraft = { draft = it },
                                ),
                                modifier = modifier,
                            )
                        }
                    }
                    if (sideBySide) {
                        Row(Modifier.fillMaxSize()) {
                            Column(Modifier.width(railWidth).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                DocumentHeader(state, tab, callbacks, Modifier.fillMaxWidth())
                                HorizontalDivider(thickness = 1.dp, color = Paper.ink)
                                actions(Modifier.fillMaxWidth())
                            }
                            VerticalDivider(thickness = 1.dp, color = Paper.rule)
                            pane(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            if (editPage == null) DocumentHeader(state, tab, callbacks, column)
                            pane(Modifier.weight(1f).then(column))
                        }
                    }
                }
                Announcement(announcer)
            }
        }
    }

    if (askDiscardEdit) {
        ConfirmDialog(
            title = stringResource(R.string.document_discard_edit_title),
            confirmLabel = stringResource(R.string.document_discard),
            dismissLabel = stringResource(R.string.document_keep_editing),
            onConfirm = { askDiscardEdit = false; editPage = null },
            onDismiss = { askDiscardEdit = false },
        )
    }
    if (renaming && doc != null) {
        RenameDialog(doc.title, onConfirm = { callbacks.onRename(it); renaming = false }, onDismiss = { renaming = false })
    }
    if (moving) {
        FolderPickerDialog(callbacks.loadFolderTree, onPick = { callbacks.onMoveTo(it); moving = false }, onDismiss = { moving = false })
    }
    if (trashing) {
        ConfirmDialog(
            title = stringResource(R.string.document_trash_title),
            confirmLabel = stringResource(R.string.document_trash),
            onConfirm = { trashing = false; callbacks.onTrash() },
            onDismiss = { trashing = false },
        )
    }
    if (choosingScript) {
        ScriptDialog(doc?.scriptOverride, onPick = { choosingScript = false; callbacks.onReadAgain(it) }, onDismiss = { choosingScript = false })
    }
    positionFor?.let { page ->
        val resources = LocalResources.current
        PositionDialog(
            page = page,
            count = state.pages.size,
            onConfirm = { to ->
                positionFor = null
                callbacks.onMovePage(page, to)
                announcer.say(resources.getString(R.string.document_moved, to + 1))
            },
            onDismiss = { positionFor = null },
        )
    }
}

/** The meta line, the text-models banner and the Pages | Text toggle. */
@Composable
private fun DocumentHeader(state: DocumentUiState, tab: DocumentTab, callbacks: DocumentCallbacks, modifier: Modifier) {
    MetaRow(state, modifier)
    ModelsBanner(state.models, callbacks.onRetryModels, modifier)
    Tabs(tab, callbacks.onTab, modifier)
}

/** Add page and Export under the grid; Share text, Language and Copy all under the text; the drag slip while dragging. */
@Composable
private fun DocumentActions(
    state: DocumentUiState,
    tab: DocumentTab,
    reorder: ReorderState,
    callbacks: DocumentCallbacks,
    onLanguage: () -> Unit,
    modifier: Modifier,
) {
    when {
        tab == DocumentTab.PAGES && reorder.isDragging -> DragBar(reorder, modifier)
        tab == DocumentTab.PAGES -> PagesBar(callbacks, modifier)
        else -> Column(modifier) {
            if (state.sharing) LoadBar(progress = null)
            TextBar(
                sharing = state.sharing,
                canCopy = state.texts.values.any { it.text.isNotBlank() },
                onShare = { callbacks.onShareText(TextPendingPolicy.ASK) },
                onLanguage = onLanguage,
                onCopyAll = callbacks.onCopyAll,
            )
        }
    }
}

/** "8 pages · 2.4 MB · 20 Oct 2026" and, at the end or (when there is no room) underneath, the TEXT flag. */
@Composable
private fun MetaRow(state: DocumentUiState, modifier: Modifier = Modifier) {
    val doc = state.document ?: return
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(doc.createdAt, locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMyyyy"), locale)
            .format(doc.createdAt.atZone(ZoneId.systemDefault()))
    }
    val size = Formatter.formatShortFileSize(context, state.storedBytes)
    EndOrBelow(modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            listOf(pluralStringResource(R.plurals.document_pages, state.pages.size, state.pages.size), size, date).joinToString(" · "),
            style = Type.figure,
            color = Paper.ink,
        )
        when {
            state.pagesReading > 0 -> Kicker(pluralStringResource(R.plurals.document_reading, state.pagesReading, state.pagesReading))
            state.textReady -> Flag(stringResource(R.string.document_flag_text))
        }
    }
}

/** Text models still downloading: a LoadBar and one line; Retry once it failed. */
@Composable
private fun ModelsBanner(models: ModuleState?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (models == null || models == ModuleState.Ready) return
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                if (models is ModuleState.Failed) {
                    Kicker(stringResource(R.string.document_models_failed))
                } else {
                    Kicker(stringResource(R.string.document_getting_models))
                    Text(stringResource(R.string.document_one_time), style = Type.body, color = Paper.muted)
                }
            }
            if (models is ModuleState.Failed && models.retryable) InkTextButton(stringResource(R.string.document_retry), onRetry)
        }
        if (models !is ModuleState.Failed && models !is ModuleState.Unsupported) {
            val progress = (models as? ModuleState.Downloading)?.let { d ->
                d.totalBytes?.takeIf { it > 0 }?.let { (d.bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
            }
            LoadBar(progress)
        }
    }
}

/** Pages | Text, printed in reverse when chosen. */
@Composable
private fun Tabs(tab: DocumentTab, onTab: (DocumentTab) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).border(1.dp, Paper.ink).selectableGroup()) {
        DocumentTab.entries.forEach { option ->
            val selected = option == tab
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .background(if (selected) Paper.ink else Paper.sheet)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onTab(option) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (selected) Paper.onInk else Paper.ink
                Icon(
                    painterResource(if (option == DocumentTab.PAGES) R.drawable.document_ic_grid else R.drawable.document_ic_text),
                    null,
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (option == DocumentTab.PAGES) R.string.document_tab_pages else R.string.document_tab_text),
                    style = Type.button.copy(fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DocumentMenu(
    pinned: Boolean,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTogglePinned: () -> Unit,
    onExport: () -> Unit,
    onReadAgain: () -> Unit,
    onTrash: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        InkIconButton(R.drawable.document_ic_more_vert, stringResource(R.string.document_more), { open = true })
        HeycamMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(R.string.document_rename, R.drawable.document_ic_edit) { open = false; onRename() }
            MenuItem(R.string.document_move_to_folder, R.drawable.document_ic_move) { open = false; onMove() }
            MenuItem(if (pinned) R.string.document_unpin else R.string.document_pin, R.drawable.document_ic_pin) { open = false; onTogglePinned() }
            MenuItem(R.string.document_export, R.drawable.document_ic_share) { open = false; onExport() }
            MenuItem(R.string.document_read_again, R.drawable.document_ic_language) { open = false; onReadAgain() }
            MenuItem(R.string.document_trash, R.drawable.document_ic_delete, accent = true) { open = false; onTrash() }
        }
    }
}

@Composable
private fun MenuItem(label: Int, icon: Int, accent: Boolean = false, onClick: () -> Unit) {
    val color = if (accent) Paper.accent else Paper.ink
    DropdownMenuItem(
        text = { Text(stringResource(label), color = color) },
        leadingIcon = { Icon(painterResource(icon), null, tint = color) },
        onClick = onClick,
    )
}

/** Add page (Scan or Photos) and Export: side by side, or stacked at large font sizes and in narrow columns. */
@Composable
private fun PagesBar(callbacks: DocumentCallbacks, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = LocalDensity.current.fontScale > LARGE_FONT_SCALE || maxWidth < 340.dp
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AddPageButton(callbacks, fillWidth = true)
                ExportButton(callbacks.onExport, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AddPageButton(callbacks, fillWidth = false)
                ExportButton(callbacks.onExport, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AddPageButton(callbacks: DocumentCallbacks, fillWidth: Boolean) {
    var addOpen by rememberSaveable { mutableStateOf(false) }
    val width = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    Box(width) {
        OutlinedButton(
            onClick = { addOpen = true },
            shape = RoundedCornerShape(2.dp),
            border = BorderStroke(1.dp, Paper.ink),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper.ink),
            modifier = width.heightIn(min = 56.dp),
        ) {
            Icon(painterResource(R.drawable.document_ic_add), null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.document_add_page), style = Type.button, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HeycamMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
            MenuItem(R.string.document_add_scan, R.drawable.document_ic_camera) { addOpen = false; callbacks.onScanMore() }
            MenuItem(R.string.document_add_photos, R.drawable.document_ic_image) { addOpen = false; callbacks.onImportMore() }
        }
    }
}

@Composable
private fun ExportButton(onExport: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onExport,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Paper.ink, contentColor = Paper.onInk),
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Icon(painterResource(R.drawable.document_ic_share), null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.document_export), style = Type.button.copy(fontSize = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Share text · Language, then Copy all. Each page has its own Edit. At large font sizes and in narrow columns Copy all
 * takes its own full-width row.
 */
@Composable
private fun TextBar(
    sharing: Boolean,
    canCopy: Boolean,
    onShare: () -> Unit,
    onLanguage: () -> Unit,
    onCopyAll: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = LocalDensity.current.fontScale > LARGE_FONT_SCALE || maxWidth < 360.dp
        val shareLabel = stringResource(R.string.document_share_txt)
        val languageLabel = stringResource(R.string.document_language)
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    LabelledIcon(R.drawable.document_ic_share, shareLabel, onShare, enabled = !sharing && canCopy)
                    LabelledIcon(R.drawable.document_ic_language, languageLabel, onLanguage)
                }
                Spacer(Modifier.height(8.dp))
                CopyAllButton(onCopyAll, canCopy, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LabelledIcon(R.drawable.document_ic_share, shareLabel, onShare, enabled = !sharing && canCopy)
                LabelledIcon(R.drawable.document_ic_language, languageLabel, onLanguage)
                CopyAllButton(onCopyAll, canCopy, Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun CopyAllButton(onCopyAll: () -> Unit, enabled: Boolean, modifier: Modifier) {
    Button(
        onClick = onCopyAll,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Paper.ink, contentColor = Paper.onInk),
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Icon(painterResource(R.drawable.document_ic_copy), null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.document_copy_all), style = Type.button.copy(fontSize = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
