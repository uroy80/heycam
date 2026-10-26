package heycam.feature.library.contents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.feature.library.R

/** Which contents dialog is open; survives rotation. */
@Stable
internal class ContentsDialogState(renameId: Long?, renameTitle: String, moveIds: List<Long>?, newFolder: Boolean) {
    var renameId by mutableStateOf(renameId)
    var renameTitle by mutableStateOf(renameTitle)
    var moveIds by mutableStateOf(moveIds)
    var newFolder by mutableStateOf(newFolder)

    fun rename(doc: DocumentSummary) { renameId = doc.id.value; renameTitle = doc.title }
    fun move(ids: Collection<DocumentId>) { moveIds = ids.map { it.value } }

    companion object {
        val Saver = listSaver<ContentsDialogState, Any?>(
            save = { listOf(it.renameId, it.renameTitle, it.moveIds?.let { ids -> ArrayList(ids) }, it.newFolder) },
            restore = {
                @Suppress("UNCHECKED_CAST")
                ContentsDialogState(it[0] as Long?, it[1] as String, it[2] as List<Long>?, it[3] as Boolean)
            },
        )
    }
}

@Composable
internal fun rememberContentsDialogs(): ContentsDialogState =
    rememberSaveable(saver = ContentsDialogState.Saver) { ContentsDialogState(null, "", null, false) }

/**
 * Rename, Move to and New folder dialogs shared by Home and the folder view. [loadFolderNames] lists the names a new
 * folder cannot take.
 */
@Composable
internal fun ContentsDialogs(
    state: ContentsDialogState,
    loadTree: suspend () -> List<FolderNode>,
    loadFolderNames: suspend () -> Set<String>,
    onRename: (DocumentId, String) -> Unit,
    onMove: (List<DocumentId>, Folder?) -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    state.renameId?.let { id ->
        NameDialog(
            title = stringResource(R.string.library_rename),
            confirmLabel = stringResource(R.string.library_save),
            initial = state.renameTitle,
            onConfirm = { onRename(DocumentId(id), it); state.renameId = null },
            onDismiss = { state.renameId = null },
            maxLength = 200,
        )
    }
    state.moveIds?.let { ids ->
        FolderPickerDialog(
            loadTree = loadTree,
            onPick = { onMove(ids.map(::DocumentId), it); state.moveIds = null },
            onDismiss = { state.moveIds = null },
        )
    }
    if (state.newFolder) {
        NameDialog(
            title = stringResource(R.string.library_new_folder),
            confirmLabel = stringResource(R.string.library_create),
            initial = "",
            onConfirm = { onCreateFolder(it); state.newFolder = false },
            onDismiss = { state.newFolder = false },
            loadTakenNames = loadFolderNames,
        )
    }
}
