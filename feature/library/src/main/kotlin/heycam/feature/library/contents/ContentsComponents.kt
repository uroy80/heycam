package heycam.feature.library.contents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import heycam.core.designsystem.Flag
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.TextState
import heycam.feature.library.R
import heycam.feature.library.ui.InkIconButton
import heycam.feature.library.ui.LibraryFormat
import heycam.feature.library.ui.Paper
import heycam.feature.library.ui.Thumb
import heycam.feature.library.ui.TopBarInsets
import heycam.feature.library.ui.Type
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** What a document row (and its TalkBack actions) can do. */
internal data class RowActions(
    val onOpen: (DocumentSummary) -> Unit = {},
    val onToggleSelect: (DocumentSummary) -> Unit = {},
    val onShare: (DocumentSummary) -> Unit = {},
    val onMove: (DocumentSummary) -> Unit = {},
    val onRename: (DocumentSummary) -> Unit = {},
    val onDelete: (DocumentSummary) -> Unit = {},
)

/** One spoken sentence per row: "Lease, 8 pages, Tuesday 20 October, 14:32". */
internal fun LibraryFormat.rowDescription(doc: DocumentSummary, today: LocalDate): String =
    listOf(doc.title, pages(doc.pageCount), spoken(doc.updatedAt, today)).joinToString(", ")

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentRow(
    doc: DocumentSummary,
    format: LibraryFormat,
    today: LocalDate,
    selected: Boolean,
    selecting: Boolean,
    actions: RowActions,
    modifier: Modifier = Modifier,
) {
    val accent = Paper.accent
    val selectLabel = stringResource(if (selected) R.string.library_deselect else R.string.library_select)
    val shareLabel = stringResource(R.string.library_share)
    val moveLabel = stringResource(R.string.library_move)
    val renameLabel = stringResource(R.string.library_rename)
    val deleteLabel = stringResource(R.string.library_delete)
    val pinnedLabel = stringResource(R.string.library_pinned)
    val selectedLabel = stringResource(R.string.library_selected)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .background(if (selected) Paper.accentWash else Paper.paper)
                .drawBehind {
                    if (selected) drawLine(accent, Offset(1.5.dp.toPx(), 0f), Offset(1.5.dp.toPx(), size.height), 3.dp.toPx())
                }
                .combinedClickable(
                    onClickLabel = if (selecting) selectLabel else stringResource(R.string.library_open),
                    onLongClickLabel = selectLabel,
                    onClick = { if (selecting) actions.onToggleSelect(doc) else actions.onOpen(doc) },
                    onLongClick = { actions.onToggleSelect(doc) },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = format.rowDescription(doc, today) + if (doc.pinned) ", $pinnedLabel" else ""
                    this.selected = selected
                    if (selected) stateDescription = selectedLabel
                    customActions = listOf(
                        CustomAccessibilityAction(selectLabel) { actions.onToggleSelect(doc); true },
                        CustomAccessibilityAction(shareLabel) { actions.onShare(doc); true },
                        CustomAccessibilityAction(moveLabel) { actions.onMove(doc); true },
                        CustomAccessibilityAction(renameLabel) { actions.onRename(doc); true },
                        CustomAccessibilityAction(deleteLabel) { actions.onDelete(doc); true },
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
                // At large font sizes the flag moves under the date instead of squeezing it.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (doc.pinned) {
                        Icon(
                            painterResource(R.drawable.library_ic_pin),
                            null,
                            tint = Paper.muted,
                            modifier = Modifier.size(14.dp).align(Alignment.CenterVertically),
                        )
                    }
                    Text(
                        format.stamp(doc.updatedAt, today) + " · " + format.size(doc.storedBytes),
                        style = Type.figure,
                        color = Paper.muted,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    if (doc.textState == TextState.DONE) Flag(stringResource(R.string.library_flag_text), Modifier.align(Alignment.CenterVertically))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${doc.pageCount}", style = Type.folio, color = Paper.ink)
                Text(stringResource(R.string.library_pp), style = Type.kicker, color = Paper.muted)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Paper.rule)
    }
}

/** A sub-folder entry: folder glyph, name, count, chevron. */
@Composable
internal fun FolderRow(folder: Folder, onOpen: (Folder) -> Unit, modifier: Modifier = Modifier) {
    val count = pluralStringResource(R.plurals.library_docs, folder.documentCount, folder.documentCount)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClickLabel = stringResource(R.string.library_open)) { onOpen(folder) }
                .semantics(mergeDescendants = true) { contentDescription = "${folder.name}, $count" }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.library_ic_folder), null, tint = Paper.ink, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Text(folder.name, style = Type.rowTitle, color = Paper.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${folder.documentCount}", style = Type.figure, color = Paper.muted)
            Icon(painterResource(R.drawable.library_ic_chevron_right), null, tint = Paper.muted, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
        HorizontalDivider(thickness = 1.dp, color = Paper.rule)
    }
}

/** Contextual bar while documents are selected: close, count, Pin, Move, Share, Delete. */
@Composable
internal fun SelectionBar(
    selected: List<DocumentSummary>,
    onClose: () -> Unit,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unpin = selected.isNotEmpty() && selected.all { it.pinned }
    Column(modifier.fillMaxWidth().background(Paper.paper).windowInsetsPadding(TopBarInsets)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            InkIconButton(R.drawable.library_ic_close, stringResource(R.string.library_clear_selection), onClose)
            Text(
                pluralStringResource(R.plurals.library_selected_count, selected.size, selected.size),
                style = Type.label,
                color = Paper.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            InkIconButton(R.drawable.library_ic_pin, stringResource(if (unpin) R.string.library_unpin else R.string.library_pin), onPin)
            InkIconButton(R.drawable.library_ic_move, stringResource(R.string.library_move), onMove)
            InkIconButton(R.drawable.library_ic_share, stringResource(R.string.library_share), onShare)
            InkIconButton(R.drawable.library_ic_delete, stringResource(R.string.library_delete), onDelete, tint = Paper.accent)
        }
        HorizontalDivider(thickness = 1.dp, color = Paper.ink)
    }
}

/** Ink slip with the action in the other theme's accent. */
@Composable
internal fun LibrarySnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier.widthIn(max = 640.dp)) { data ->
        Snackbar(
            data,
            shape = RectangleShape,
            containerColor = Paper.ink,
            contentColor = Paper.onInk,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
        )
    }
}

/** Shows [messages] one at a time; "Undo" on a trash message restores the documents. */
@Composable
internal fun LibraryMessagesEffect(messages: Flow<LibraryMessage>, host: SnackbarHostState, onUndoTrash: (List<DocumentId>) -> Unit) {
    val resources = LocalResources.current
    val undo by rememberUpdatedState(onUndoTrash)
    LaunchedEffect(messages, host) {
        messages.collect { message ->
            when (message) {
                is LibraryMessage.Trashed -> {
                    val result = host.showSnackbar(
                        resources.getQuantityString(R.plurals.library_trashed, message.ids.size, message.ids.size),
                        actionLabel = resources.getString(R.string.library_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) undo(message.ids)
                }
                is LibraryMessage.Moved -> host.showSnackbar(
                    resources.getString(R.string.library_moved_to, message.folderName ?: resources.getString(R.string.library_contents)),
                )
                LibraryMessage.FolderLimit -> host.showSnackbar(resources.getString(R.string.library_folder_limit))
                LibraryMessage.NameInUse -> host.showSnackbar(resources.getString(R.string.library_name_in_use))
                LibraryMessage.Failed -> host.showSnackbar(resources.getString(R.string.library_failed))
            }
        }
    }
}
