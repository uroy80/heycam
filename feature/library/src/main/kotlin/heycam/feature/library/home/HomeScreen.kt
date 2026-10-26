package heycam.feature.library.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import heycam.core.designsystem.Flag
import heycam.core.designsystem.Kicker
import heycam.core.designsystem.SectionHeader
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.FolderId
import heycam.core.model.SortOrder
import heycam.core.model.TextState
import heycam.feature.library.R
import heycam.feature.library.contents.ContentsDialogState
import heycam.feature.library.contents.ContentsDialogs
import heycam.feature.library.contents.DocumentRow
import heycam.feature.library.contents.FolderNode
import heycam.feature.library.contents.LibraryMessagesEffect
import heycam.feature.library.contents.LibrarySnackbarHost
import heycam.feature.library.contents.RowActions
import heycam.feature.library.contents.SelectionBar
import heycam.feature.library.contents.rememberContentsDialogs
import heycam.feature.library.contents.rowDescription
import heycam.feature.library.ui.InkTextButton
import heycam.feature.library.ui.LibraryFormat
import heycam.feature.library.ui.Paper
import heycam.feature.library.ui.ScreenInsets
import heycam.feature.library.ui.Thumb
import heycam.feature.library.ui.Type
import java.time.LocalDate

/** Everything Home can ask for. Defaults are no-ops so previews and tests pass only what they need. */
internal data class HomeCallbacks(
    val onSearch: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onOpenDocument: (DocumentId) -> Unit = {},
    val onExport: (List<DocumentId>) -> Unit = {},
    val onOpenFolder: (FolderId) -> Unit = {},
    val onOpenTrash: () -> Unit = {},
    val onSort: (SortOrder) -> Unit = {},
    val onToggleSelect: (DocumentId) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onTogglePinned: (List<DocumentSummary>) -> Unit = {},
    val onTrash: (List<DocumentId>) -> Unit = {},
    val onRename: (DocumentId, String) -> Unit = { _, _ -> },
    val onMove: (List<DocumentId>, Folder?) -> Unit = { _, _ -> },
    val onCreateFolder: (String) -> Unit = {},
    val loadFolderTree: suspend () -> List<FolderNode> = { emptyList() },
    /** Names already used by top-level folders, for the "New folder" dialog. */
    val loadFolderNames: suspend () -> Set<String> = { emptySet() },
)

@Composable
internal fun HomeDestination(
    toolsTab: @Composable () -> Unit,
    scanStatus: @Composable () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onOpenDocument: (DocumentId) -> Unit,
    onExport: (List<DocumentId>) -> Unit,
    onOpenFolder: (FolderId) -> Unit,
    onOpenTrash: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LibraryMessagesEffect(viewModel.messages, snackbar, viewModel::undoTrash)
    HomeScreen(
        state = state,
        today = LocalDate.now(),
        toolsTab = toolsTab,
        scanStatus = scanStatus,
        snackbar = snackbar,
        callbacks = HomeCallbacks(
            onSearch = onSearch,
            onSettings = onSettings,
            onScan = onScan,
            onImport = onImport,
            onOpenDocument = onOpenDocument,
            onExport = { ids -> viewModel.clearSelection(); onExport(ids) },
            onOpenFolder = onOpenFolder,
            onOpenTrash = onOpenTrash,
            onSort = viewModel::setSort,
            onToggleSelect = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onTogglePinned = viewModel::togglePinned,
            onTrash = viewModel::trash,
            onRename = viewModel::rename,
            onMove = viewModel::moveTo,
            onCreateFolder = { viewModel.createFolder(it, parent = null, parentDepth = 0) },
            loadFolderTree = viewModel::folderTree,
            loadFolderNames = { viewModel.folderNames(parent = null) },
        ),
    )
}

/** Below this window height (a phone on its side) the masthead, dateline and tabs scroll away with the contents. */
internal val CompactHeight = 480.dp

/** Home / Contents (S1) and its first-run state (S2). */
@Composable
internal fun HomeScreen(
    state: HomeUiState,
    today: LocalDate,
    toolsTab: @Composable () -> Unit,
    scanStatus: @Composable () -> Unit,
    snackbar: SnackbarHostState,
    callbacks: HomeCallbacks,
    dialogs: ContentsDialogState = rememberContentsDialogs(),
) {
    val context = LocalContext.current
    val format = remember(context) { LibraryFormat(context) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.CONTENTS) }
    val selecting = state.selection.isNotEmpty()
    BackHandler(enabled = selecting) { callbacks.onClearSelection() }
    BackHandler(enabled = !selecting && tab == HomeTab.TOOLS) { tab = HomeTab.CONTENTS }

    val rowActions = RowActions(
        onOpen = { callbacks.onOpenDocument(it.id) },
        onToggleSelect = { callbacks.onToggleSelect(it.id) },
        onShare = { callbacks.onExport(listOf(it.id)) },
        onMove = { dialogs.move(listOf(it.id)) },
        onRename = { dialogs.rename(it) },
        onDelete = { callbacks.onTrash(listOf(it.id)) },
    )
    val column = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < CompactHeight
        // Masthead, dateline, tabs and the scanner bar: pinned on top, or in a short window the head of the list.
        val chrome: @Composable () -> Unit = {
            if (compact && !selecting) {
                Masthead(callbacks.onSearch, callbacks.onSettings, column, padForBars = false)
                Dateline(state.stats, format.dateline(today), format.datelineSpoken(today), column)
            }
            if (!selecting) {
                HomeTabs(tab, { tab = it }, column) {
                    if (tab == HomeTab.CONTENTS) {
                        if (!state.isEmpty) SortControl(state.sort, callbacks.onSort)
                        ContentsMenu(onNewFolder = { dialogs.newFolder = true }, onTrash = callbacks.onOpenTrash)
                    }
                }
            }
            Box(column) { scanStatus() }
        }
        val chromeScrolls = compact && tab == HomeTab.CONTENTS && state.loaded

        Scaffold(
            containerColor = Paper.paper,
            contentWindowInsets = ScreenInsets,
            topBar = {
                if (selecting) {
                    SelectionBar(
                        selected = state.selected,
                        onClose = callbacks.onClearSelection,
                        onPin = { callbacks.onTogglePinned(state.selected) },
                        onMove = { dialogs.move(state.selection) },
                        onShare = { callbacks.onExport(state.selection.toList()) },
                        onDelete = { callbacks.onTrash(state.selection.toList()) },
                    )
                } else if (!compact) {
                    Column(Modifier.background(Paper.paper)) {
                        Masthead(callbacks.onSearch, callbacks.onSettings, column)
                        Dateline(state.stats, format.dateline(today), format.datelineSpoken(today), column)
                    }
                }
            },
            bottomBar = { if (state.loaded && !state.isEmpty && !selecting) ScanBar(callbacks.onImport, callbacks.onScan, column) },
            snackbarHost = { LibrarySnackbarHost(snackbar) },
        ) { inner ->
            Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
                if (!chromeScrolls) chrome()
                when {
                    tab == HomeTab.TOOLS -> Box(Modifier.weight(1f).then(column)) { toolsTab() }
                    !state.loaded -> Spacer(Modifier.weight(1f))
                    state.isEmpty -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (chromeScrolls) chrome()
                        FirstRun(callbacks.onScan, callbacks.onImport, column)
                    }
                    else -> ContentsList(
                        state = state,
                        format = format,
                        today = today,
                        actions = rowActions,
                        onOpenFolder = callbacks.onOpenFolder,
                        header = if (chromeScrolls) chrome else null,
                        modifier = Modifier.weight(1f).then(column),
                    )
                }
            }
        }
    }
    ContentsDialogs(
        state = dialogs,
        loadTree = callbacks.loadFolderTree,
        loadFolderNames = callbacks.loadFolderNames,
        onRename = callbacks.onRename,
        onMove = callbacks.onMove,
        onCreateFolder = callbacks.onCreateFolder,
    )
}

@Composable
private fun ContentsList(
    state: HomeUiState,
    format: LibraryFormat,
    today: LocalDate,
    actions: RowActions,
    onOpenFolder: (FolderId) -> Unit,
    header: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val selecting = state.selection.isNotEmpty()
    val sectionPadding = Modifier.padding(horizontal = 16.dp)
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (header != null) item(key = "chrome") { Column { header() } }
        state.latest?.let { latest ->
            item(key = "lead") {
                LeadStory(latest, state.latestFolder, format, today, latest.id in state.selection, selecting, actions)
            }
        }
        if (state.pinned.isNotEmpty()) {
            item(key = "pinned") {
                SectionHeader(stringResource(R.string.library_pinned), sectionPadding, count = state.pinned.size)
            }
            items(state.pinned, key = { "d${it.id.value}" }) {
                DocumentRow(it, format, today, it.id in state.selection, selecting, actions)
            }
        }
        state.sections.forEach { section ->
            item(key = "f${section.folder.id.value}") {
                SectionHeader(
                    text = section.folder.name,
                    modifier = sectionPadding,
                    count = section.folder.documentCount,
                    // TalkBack says what the tap does: "double-tap to open folder".
                    onClickLabel = stringResource(R.string.library_open_folder),
                    onClick = { onOpenFolder(section.folder.id) },
                )
            }
            items(section.preview, key = { "d${it.id.value}" }) {
                DocumentRow(it, format, today, it.id in state.selection, selecting, actions)
            }
        }
        if (state.documents.isNotEmpty()) {
            if (state.pinned.isNotEmpty() || state.sections.isNotEmpty()) {
                item(key = "loose") {
                    SectionHeader(stringResource(R.string.library_documents), sectionPadding, count = state.documents.size)
                }
            }
            items(state.documents, key = { "d${it.id.value}" }) {
                DocumentRow(it, format, today, it.id in state.selection, selecting, actions)
            }
        }
    }
}

/**
 * The newest document as the front-page lead: kicker, big thumb, serif headline, Open · Share. TalkBack hears the
 * story once, as one node with the row actions; Open and Share stay their own buttons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeadStory(
    doc: DocumentSummary,
    folderName: String?,
    format: LibraryFormat,
    today: LocalDate,
    selected: Boolean,
    selecting: Boolean,
    actions: RowActions,
) {
    val latest = stringResource(R.string.library_latest)
    val selectLabel = stringResource(if (selected) R.string.library_deselect else R.string.library_select)
    val renameLabel = stringResource(R.string.library_rename)
    val moveLabel = stringResource(R.string.library_move)
    val deleteLabel = stringResource(R.string.library_delete)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Paper.accentWash else Paper.paper)
            .combinedClickable(
                onClickLabel = stringResource(R.string.library_open),
                onLongClickLabel = selectLabel,
                onClick = { if (selecting) actions.onToggleSelect(doc) else actions.onOpen(doc) },
                onLongClick = { actions.onToggleSelect(doc) },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "$latest: " + format.rowDescription(doc, today)
                this.selected = selected
                customActions = listOf(
                    CustomAccessibilityAction(selectLabel) { actions.onToggleSelect(doc); true },
                    CustomAccessibilityAction(moveLabel) { actions.onMove(doc); true },
                    CustomAccessibilityAction(renameLabel) { actions.onRename(doc); true },
                    CustomAccessibilityAction(deleteLabel) { actions.onDelete(doc); true },
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // The story is spoken once, by the node above; these only draw it.
        Box(Modifier.clearAndSetSemantics {}) { Kicker(latest) }
        Spacer(Modifier.height(10.dp))
        Row {
            Box(Modifier.clearAndSetSemantics {}) { Thumb(doc.coverThumbPath, doc.updatedAt, 104.dp, 140.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Column(Modifier.clearAndSetSemantics {}) {
                    Text(doc.title, style = Type.title, color = Paper.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOf(
                            stringResource(R.string.library_pp_short, doc.pageCount),
                            format.stamp(doc.updatedAt, today),
                            format.size(doc.storedBytes),
                        ).joinToString(" · "),
                        style = Type.figure,
                        color = Paper.ink,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (doc.textState == TextState.DONE) Flag(stringResource(R.string.library_flag_text))
                        if (folderName != null) Flag(folderName, outlined = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    InkTextButton(stringResource(R.string.library_open), { actions.onOpen(doc) })
                    InkTextButton(stringResource(R.string.library_share), { actions.onShare(doc) })
                }
            }
        }
    }
}
