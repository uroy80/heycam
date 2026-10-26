package heycam.feature.library.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.feature.library.R
import heycam.feature.library.contents.ConfirmDialog
import heycam.feature.library.contents.LibrarySnackbarHost
import heycam.feature.library.ui.ArticleTopBar
import heycam.feature.library.ui.InkIconButton
import heycam.feature.library.ui.InkTextButton
import heycam.feature.library.ui.LibraryFormat
import heycam.feature.library.ui.Paper
import heycam.feature.library.ui.ScreenInsets
import heycam.feature.library.ui.Thumb
import heycam.feature.library.ui.TopBarInsets
import heycam.feature.library.ui.Type
import kotlinx.coroutines.flow.Flow

@Composable
internal fun TrashDestination(onBack: () -> Unit, viewModel: TrashViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TrashMessagesEffect(viewModel.messages, snackbar)
    TrashScreen(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onToggleSelect = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onRestore = viewModel::restore,
        onDeleteForever = viewModel::deleteForever,
        onEmpty = viewModel::emptyTrash,
    )
}

@Composable
private fun TrashMessagesEffect(messages: Flow<TrashMessage>, host: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(messages, host) {
        messages.collect {
            host.showSnackbar(
                when (it) {
                    is TrashMessage.Restored -> resources.getQuantityString(R.plurals.library_restored, it.count, it.count)
                    is TrashMessage.Deleted -> resources.getQuantityString(R.plurals.library_deleted, it.count, it.count)
                    TrashMessage.Failed -> resources.getString(R.string.library_failed)
                },
            )
        }
    }
}

/** Trash (S5): tap to select; restore or delete for good; Empty clears everything after a confirm. */
@Composable
internal fun TrashScreen(
    state: TrashUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onToggleSelect: (DocumentId) -> Unit,
    onClearSelection: () -> Unit,
    onRestore: (List<DocumentId>) -> Unit,
    onDeleteForever: (List<DocumentId>) -> Unit,
    onEmpty: () -> Unit,
) {
    val context = LocalContext.current
    val format = remember(context) { LibraryFormat(context) }
    val selecting = state.selection.isNotEmpty()
    var confirmIds by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = selecting) { onClearSelection() }
    val column = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)

    Scaffold(
        containerColor = Paper.paper,
        contentWindowInsets = ScreenInsets,
        topBar = {
            val bar = Modifier.background(Paper.paper).windowInsetsPadding(TopBarInsets)
            if (selecting) {
                ArticleTopBar(
                    title = pluralStringResource(R.plurals.library_selected_count, state.selection.size, state.selection.size),
                    navigationIcon = R.drawable.library_ic_close,
                    navigationLabel = stringResource(R.string.library_clear_selection),
                    onNavigate = onClearSelection,
                    modifier = bar,
                ) {
                    InkIconButton(R.drawable.library_ic_restore, stringResource(R.string.library_restore), { onRestore(state.selection.toList()) })
                    InkIconButton(
                        R.drawable.library_ic_delete,
                        stringResource(R.string.library_delete_forever),
                        { confirmIds = state.selection.map { it.value } },
                        tint = Paper.accent,
                    )
                }
            } else {
                ArticleTopBar(
                    title = stringResource(R.string.library_trash),
                    navigationIcon = R.drawable.library_ic_arrow_back,
                    navigationLabel = stringResource(R.string.library_back),
                    onNavigate = onBack,
                    modifier = bar,
                ) {
                    if (state.documents.isNotEmpty()) {
                        val emptyTrash = stringResource(R.string.library_empty_trash_spoken)
                        InkTextButton(
                            stringResource(R.string.library_empty_trash),
                            { confirmEmpty = true },
                            Modifier.semantics { contentDescription = emptyTrash },
                        )
                    }
                }
            }
        },
        snackbarHost = { LibrarySnackbarHost(snackbar) },
    ) { inner ->
        Box(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            if (state.loaded && state.documents.isEmpty()) {
                Text(stringResource(R.string.library_trash_is_empty), style = Type.body, color = Paper.muted, modifier = column.padding(24.dp))
            } else {
                LazyColumn(column, contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.documents, key = { it.id.value }) { doc ->
                        TrashRow(
                            doc = doc,
                            format = format,
                            selected = doc.id in state.selection,
                            onToggle = { onToggleSelect(doc.id) },
                            onRestore = { onRestore(listOf(doc.id)) },
                            onDeleteForever = { confirmIds = listOf(doc.id.value) },
                        )
                    }
                }
            }
        }
    }
    confirmIds?.let { ids ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.library_delete_forever_title, ids.size, ids.size),
            confirmLabel = stringResource(R.string.library_delete),
            onConfirm = { confirmIds = null; onDeleteForever(ids.map(::DocumentId)) },
            onDismiss = { confirmIds = null },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.library_delete_forever_title, state.documents.size, state.documents.size),
            confirmLabel = stringResource(R.string.library_delete),
            onConfirm = { confirmEmpty = false; onEmpty() },
            onDismiss = { confirmEmpty = false },
        )
    }
}

@Composable
private fun TrashRow(
    doc: DocumentSummary,
    format: LibraryFormat,
    selected: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val restore = stringResource(R.string.library_restore)
    val deleteForever = stringResource(R.string.library_delete_forever)
    val deleted = doc.trashedAt?.let { stringResource(R.string.library_deleted_on, format.day(it)) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .background(if (selected) Paper.accentWash else Paper.paper)
                .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(doc.title, format.pages(doc.pageCount), deleted).joinToString(", ")
                    customActions = listOf(
                        CustomAccessibilityAction(restore) { onRestore(); true },
                        CustomAccessibilityAction(deleteForever) { onDeleteForever(); true },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Thumb(doc.coverThumbPath, doc.updatedAt, 44.dp, 60.dp)
                if (selected) {
                    Box(Modifier.size(44.dp, 60.dp).background(Paper.ink.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.library_ic_check), null, tint = Paper.onInk, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.title, style = Type.rowTitle, color = Paper.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(deleted, format.size(doc.storedBytes)).joinToString(" · "), style = Type.figure, color = Paper.muted)
            }
            InkIconButton(R.drawable.library_ic_restore, restore, onRestore)
        }
        HorizontalDivider(thickness = 1.dp, color = Paper.rule)
    }
}
