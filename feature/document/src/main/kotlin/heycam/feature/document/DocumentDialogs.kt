package heycam.feature.document

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import heycam.core.model.Folder
import heycam.core.model.OcrScript
import heycam.feature.document.ui.LoadBar
import heycam.feature.document.ui.Paper
import heycam.feature.document.ui.Type

/** "Rename": one field, Save stays off while it is blank. */
@Composable
internal fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    val valid = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Paper.paper,
        title = { Text(stringResource(R.string.document_rename), style = Type.title, color = Paper.ink) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.length <= MAX_TITLE) value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.document_name)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = valid, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.document_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.document_cancel)) }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** "Move to": Contents (top level) and every folder, indented by depth. */
@Composable
internal fun FolderPickerDialog(loadTree: suspend () -> List<FolderNode>, onPick: (Folder?) -> Unit, onDismiss: () -> Unit) {
    var tree by remember { mutableStateOf<List<FolderNode>?>(null) }
    LaunchedEffect(Unit) { tree = runCatching { loadTree() }.getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Paper.paper,
        title = { Text(stringResource(R.string.document_move_to), style = Type.title, color = Paper.ink) },
        text = {
            val nodes = tree
            if (nodes == null) {
                LoadBar(progress = null)
            } else {
                LazyColumn {
                    item { FolderRow(stringResource(R.string.document_contents), 0) { onPick(null) } }
                    items(nodes, key = { it.folder.id.value }) { node -> FolderRow(node.folder.name, node.depth + 1) { onPick(node.folder) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.document_cancel)) }
        },
    )
}

@Composable
private fun FolderRow(name: String, depth: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = (depth * 20).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.document_ic_move), null, tint = Paper.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(name, style = Type.body, color = Paper.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** TalkBack's "Move to position…": a number from 1 to [count]. */
@Composable
internal fun PositionDialog(page: Int, count: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val position = parsePosition(value, page, count)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Paper.paper,
        title = { Text(stringResource(R.string.document_position_title, page + 1), style = Type.title, color = Paper.ink) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { text -> if (text.length <= 4 && text.all(Char::isDigit)) value = text },
                singleLine = true,
                label = { Text(stringResource(R.string.document_position_label, count)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { position?.let(onConfirm) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { position?.let(onConfirm) }, enabled = position != null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.document_move))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.document_cancel)) }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The typed 1-based position as a 0-based index; null when it is out of range or where the page already is. */
internal fun parsePosition(text: String, page: Int, count: Int): Int? =
    text.trim().toIntOrNull()?.takeIf { it in 1..count && it != page + 1 }?.minus(1)

/**
 * "Read again as": Auto or one of the five scripts. A row only chooses; "Read again" starts reading every page (which
 * replaces any corrections), so checking which script is set never costs anything.
 */
@Composable
internal fun ScriptDialog(current: OcrScript?, onPick: (OcrScript?) -> Unit, onDismiss: () -> Unit) {
    val options = listOf<OcrScript?>(null) + OcrScript.entries
    var chosen by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Paper.paper,
        title = { Text(stringResource(R.string.document_read_again_as), style = Type.title, color = Paper.ink) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { script ->
                    val selected = script == chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { chosen = script }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Paper.accent))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(script.label), style = Type.body, color = Paper.ink)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(chosen) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.document_read_again_confirm), color = Paper.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.document_cancel)) }
        },
    )
}

/** Export asked first because some pages are still being read. */
@Composable
internal fun TextPendingDialog(pages: Int, onWait: () -> Unit, onShareNow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Paper.paper,
        title = { Text(pluralStringResource(R.plurals.document_text_pending_title, pages, pages), style = Type.title, color = Paper.ink) },
        confirmButton = {
            TextButton(onClick = onWait, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.document_wait)) }
        },
        dismissButton = {
            TextButton(onClick = onShareNow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.document_share_now)) }
        },
    )
}

internal val OcrScript?.label: Int
    get() = when (this) {
        null -> R.string.document_script_auto
        OcrScript.LATIN -> R.string.document_script_latin
        OcrScript.DEVANAGARI -> R.string.document_script_devanagari
        OcrScript.CHINESE -> R.string.document_script_chinese
        OcrScript.JAPANESE -> R.string.document_script_japanese
        OcrScript.KOREAN -> R.string.document_script_korean
    }

private const val MAX_TITLE = 200
