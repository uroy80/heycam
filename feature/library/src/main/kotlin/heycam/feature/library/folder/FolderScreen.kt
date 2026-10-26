package heycam.feature.library.folder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import heycam.core.designsystem.HeycamMenu
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.FolderId
import heycam.core.model.SortOrder
import heycam.feature.library.R
import heycam.feature.library.contents.ConfirmDialog
import heycam.feature.library.contents.ContentsDialogState
import heycam.feature.library.contents.ContentsDialogs
import heycam.feature.library.contents.DocumentRow
import heycam.feature.library.contents.FolderNode
import heycam.feature.library.contents.FolderRow
import heycam.feature.library.contents.LibraryMessagesEffect
import heycam.feature.library.contents.LibrarySnackbarHost
import heycam.feature.library.contents.NameDialog
import heycam.feature.library.contents.RowActions
import heycam.feature.library.contents.SelectionBar
import heycam.feature.library.contents.rememberContentsDialogs
import heycam.feature.library.home.SortControl
import heycam.feature.library.ui.ArticleTopBar
import heycam.feature.library.ui.InkIconButton
import heycam.feature.library.ui.LibraryFormat
import heycam.feature.library.ui.Paper
import heycam.feature.library.ui.ScreenInsets
import heycam.feature.library.ui.TopBarInsets
import heycam.feature.library.ui.Type
import java.time.LocalDate

internal data class FolderCallbacks(
    val onBack: () -> Unit = {},
    val onOpenDocument: (DocumentId) -> Unit = {},
    val onOpenFolder: (FolderId) -> Unit = {},
    val onExport: (List<DocumentId>) -> Unit = {},
    val onSort: (SortOrder) -> Unit = {},
    val onToggleSelect: (DocumentId) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onTogglePinned: (List<DocumentSummary>) -> Unit = {},
    val onTrash: (List<DocumentId>) -> Unit = {},
    val onRename: (DocumentId, String) -> Unit = { _, _ -> },
    val onMove: (List<DocumentId>, Folder?) -> Unit = { _, _ -> },
    val onCreateSubfolder: (String) -> Unit = {},
    val onRenameFolder: (String) -> Unit = {},
    val onDeleteFolder: () -> Unit = {},
    val loadFolderTree: suspend () -> List<FolderNode> = { emptyList() },
    /** Names already used inside this folder, for "New folder". */
    val loadSubfolderNames: suspend () -> Set<String> = { emptySet() },
    /** Names already used next to this folder, for "Rename folder". */
    val loadSiblingNames: suspend () -> Set<String> = { emptySet() },
)

@Composable
internal fun FolderDestination(
    onBack: () -> Unit,
    onOpenDocument: (DocumentId) -> Unit,
    onOpenFolder: (FolderId) -> Unit,
    onExport: (List<DocumentId>) -> Unit,
    viewModel: FolderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LibraryMessagesEffect(viewModel.messages, snackbar, viewModel::undoTrash)
    LaunchedEffect(state.missing) { if (state.missing) onBack() }
    FolderScreen(
        state = state,
        today = LocalDate.now(),
        snackbar = snackbar,
        callbacks = FolderCallbacks(
            onBack = onBack,
            onOpenDocument = onOpenDocument,
            onOpenFolder = onOpenFolder,
            onExport = { ids -> viewModel.clearSelection(); onExport(ids) },
            onSort = viewModel::setSort,
            onToggleSelect = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onTogglePinned = viewModel::togglePinned,
            onTrash = viewModel::trash,
            onRename = viewModel::rename,
            onMove = viewModel::moveTo,
            onCreateSubfolder = viewModel::createSubfolder,
            onRenameFolder = viewModel::renameFolder,
            onDeleteFolder = viewModel::deleteFolder,
            loadFolderTree = viewModel::folderTree,
            loadSubfolderNames = viewModel::subfolderNames,
            loadSiblingNames = viewModel::siblingNames,
        ),
    )
}

/** Folder view (S4). */
@Composable
internal fun FolderScreen(
    state: FolderUiState,
    today: LocalDate,
    snackbar: SnackbarHostState,
    callbacks: FolderCallbacks,
    dialogs: ContentsDialogState = rememberContentsDialogs(),
) {
    val context = LocalContext.current
    val format = remember(context) { LibraryFormat(context) }
    val selecting = state.selection.isNotEmpty()
    var renamingFolder by rememberSaveable { mutableStateOf(false) }
    var deletingFolder by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = selecting) { callbacks.onClearSelection() }
    val column = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)
    val actions = RowActions(
        onOpen = { callbacks.onOpenDocument(it.id) },
        onToggleSelect = { callbacks.onToggleSelect(it.id) },
        onShare = { callbacks.onExport(listOf(it.id)) },
        onMove = { dialogs.move(listOf(it.id)) },
        onRename = { dialogs.rename(it) },
        onDelete = { callbacks.onTrash(listOf(it.id)) },
    )
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
            } else {
                ArticleTopBar(
                    title = state.folder?.name.orEmpty(),
                    kicker = state.path.takeIf { it.isNotEmpty() }?.joinToString(" › ") { it.name },
                    navigationIcon = R.drawable.library_ic_arrow_back,
                    navigationLabel = stringResource(R.string.library_back),
                    onNavigate = callbacks.onBack,
                    modifier = Modifier.background(Paper.paper).windowInsetsPadding(TopBarInsets),
                ) {
                    if (state.documents.isNotEmpty()) SortControl(state.sort, callbacks.onSort)
                    FolderMenu(
                        canNest = state.canNest,
                        onNewFolder = { dialogs.newFolder = true },
                        onRename = { renamingFolder = true },
                        onDelete = { deletingFolder = true },
                    )
                }
            }
        },
        snackbarHost = { LibrarySnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            if (state.loaded && state.subfolders.isEmpty() && state.documents.isEmpty()) {
                Box(column.padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.library_folder_empty), style = Type.body, color = Paper.muted)
                }
            } else {
                LazyColumn(column, contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.subfolders, key = { "f${it.id.value}" }) { FolderRow(it, { f -> callbacks.onOpenFolder(f.id) }) }
                    items(state.documents, key = { "d${it.id.value}" }) {
                        DocumentRow(it, format, today, it.id in state.selection, selecting, actions)
                    }
                }
            }
        }
    }
    ContentsDialogs(
        state = dialogs,
        loadTree = callbacks.loadFolderTree,
        loadFolderNames = callbacks.loadSubfolderNames,
        onRename = callbacks.onRename,
        onMove = callbacks.onMove,
        onCreateFolder = callbacks.onCreateSubfolder,
    )
    if (renamingFolder) {
        NameDialog(
            title = stringResource(R.string.library_rename_folder),
            confirmLabel = stringResource(R.string.library_save),
            initial = state.folder?.name.orEmpty(),
            onConfirm = { callbacks.onRenameFolder(it); renamingFolder = false },
            onDismiss = { renamingFolder = false },
            loadTakenNames = callbacks.loadSiblingNames,
        )
    }
    if (deletingFolder) {
        val destination = state.path.lastOrNull()?.name ?: stringResource(R.string.library_contents)
        ConfirmDialog(
            title = stringResource(R.string.library_delete_folder_title),
            text = stringResource(R.string.library_delete_folder_text, destination),
            confirmLabel = stringResource(R.string.library_delete),
            onConfirm = { deletingFolder = false; callbacks.onDeleteFolder() },
            onDismiss = { deletingFolder = false },
        )
    }
}

@Composable
private fun FolderMenu(canNest: Boolean, onNewFolder: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        InkIconButton(R.drawable.library_ic_more_vert, stringResource(R.string.library_more), { open = true })
        HeycamMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canNest) {
                DropdownMenuItem(text = { Text(stringResource(R.string.library_new_folder)) }, onClick = { open = false; onNewFolder() })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.library_rename_folder)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_delete_folder), color = Paper.accent) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}
