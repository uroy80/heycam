package heycam.feature.library.contents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import heycam.core.model.Folder
import heycam.feature.library.R
import heycam.feature.library.ui.Paper
import heycam.feature.library.ui.Type
import kotlin.coroutines.cancellation.CancellationException

/**
 * Single-field dialog for "New folder" and "Rename". Confirm stays disabled while the name is blank or, for a folder,
 * already used next to it: [loadTakenNames] gives those names as [folderNameKey]s.
 */
@Composable
internal fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int = 120,
    loadTakenNames: suspend () -> Set<String> = { emptySet() },
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    var taken by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) {
        // Without the list saving still refuses a taken name; the dialog just cannot say so up front.
        taken = try {
            loadTakenNames()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
    }
    val focus = remember { FocusRequester() }
    val inUse = value.text.isNotBlank() && folderNameKey(value.text) in taken
    val valid = value.text.isNotBlank() && !inUse
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Paper.paper,
        title = { Text(title, style = Type.title, color = Paper.ink) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.length <= maxLength) value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.library_name)) },
                isError = inUse,
                supportingText = if (inUse) {
                    { Text(stringResource(R.string.library_name_in_use)) }
                } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = valid, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_cancel)) }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Yes/no for anything that cannot be undone. */
@Composable
internal fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Paper.paper,
        title = { Text(title, style = Type.title, color = Paper.ink) },
        text = text?.let { { Text(it, style = Type.body, color = Paper.ink) } },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel, color = Paper.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

/** "Move to": Contents (top level) and every folder, indented by depth. */
@Composable
internal fun FolderPickerDialog(
    loadTree: suspend () -> List<FolderNode>,
    onPick: (Folder?) -> Unit,
    onDismiss: () -> Unit,
) {
    var tree by remember { mutableStateOf<List<FolderNode>?>(null) }
    LaunchedEffect(Unit) { tree = runCatching { loadTree() }.getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Paper.paper,
        title = { Text(stringResource(R.string.library_move_to), style = Type.title, color = Paper.ink) },
        text = {
            val nodes = tree
            if (nodes == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Paper.accent, trackColor = Paper.rule)
            } else {
                LazyColumn {
                    item { PickerRow(stringResource(R.string.library_contents), 0) { onPick(null) } }
                    items(nodes, key = { it.folder.id.value }) { node -> PickerRow(node.folder.name, node.depth + 1) { onPick(node.folder) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

@Composable
private fun PickerRow(name: String, depth: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = (depth * 20).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.library_ic_folder), null, tint = Paper.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(name, style = Type.body, color = Paper.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
