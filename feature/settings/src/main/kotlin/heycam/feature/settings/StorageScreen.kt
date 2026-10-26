package heycam.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.HeycamDialog
import heycam.core.designsystem.HeycamSize
import heycam.core.designsystem.HeycamSnackbarHost
import heycam.core.designsystem.LoadBar
import heycam.core.designsystem.TextButton
import heycam.core.designsystem.showHeycamSnackbar

@Composable
internal fun StorageRouteScreen(
    onBack: () -> Unit,
    onOpenTrash: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.messages.collect { message ->
                val text = when (message) {
                    is StorageMessage.Freed -> if (message.bytes > 0) {
                        resources.getString(R.string.storage_freed, Formatter.formatShortFileSize(context, message.bytes))
                    } else {
                        resources.getString(R.string.storage_nothing_to_clear)
                    }
                    StorageMessage.TrashEmptied -> resources.getString(R.string.storage_trash_emptied)
                    StorageMessage.Failed -> resources.getString(R.string.storage_failed)
                }
                snackbar.showHeycamSnackbar(text, isError = message == StorageMessage.Failed)
            }
        }
    }
    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose { }
    }
    StorageScreen(
        state = state,
        onBack = onBack,
        onEmptyTrash = viewModel::emptyTrash,
        onClearTemporaryFiles = viewModel::clearTemporaryFiles,
        onOpenTrash = onOpenTrash,
        snackbar = snackbar,
    )
}

/** Storage (S14): one figure per kind of file, with the one action that frees it. */
@Composable
internal fun StorageScreen(
    state: StorageUiState,
    onBack: () -> Unit,
    onEmptyTrash: () -> Unit,
    onClearTemporaryFiles: () -> Unit,
    onOpenTrash: () -> Unit,
    snackbar: SnackbarHostState,
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val context = LocalContext.current
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    fun size(bytes: Long?) = bytes?.let { Formatter.formatShortFileSize(context, it) }
    Box(Modifier.fillMaxSize()) {
        SettingsPage(title = stringResource(R.string.storage_title), onBack = onBack, windowInsets = windowInsets) {
            val usage = state.usage
            SectionHead(stringResource(R.string.storage_on_this_phone))
            if (state.busy) LoadBar()
            FigureRow(stringResource(R.string.storage_documents), size(usage?.pagesBytes))
            RowDivider()
            FigureRow(
                label = stringResource(R.string.storage_trash),
                figure = size(usage?.trashBytes),
                action = stringResource(R.string.storage_empty),
                actionEnabled = !state.busy && state.trashCount > 0,
                onAction = { confirmEmpty = true },
            )
            RowDivider()
            FigureRow(
                label = stringResource(R.string.storage_temporary),
                figure = size(usage?.cacheBytes),
                action = stringResource(R.string.storage_clear),
                actionEnabled = !state.busy && (usage == null || usage.cacheBytes > 0),
                onAction = onClearTemporaryFiles,
            )
            RowDivider()
            LinkRow(label = stringResource(R.string.storage_open_trash), onClick = onOpenTrash)
        }
        HeycamSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        )
    }
    if (confirmEmpty) {
        // The Trash screen asks the same question for the same action.
        HeycamDialog(
            title = pluralStringResource(R.plurals.storage_delete_title, state.trashCount, state.trashCount),
            confirmLabel = stringResource(R.string.storage_delete),
            onConfirm = {
                confirmEmpty = false
                onEmptyTrash()
            },
            onDismissRequest = { confirmEmpty = false },
            dismissLabel = stringResource(R.string.settings_cancel),
        )
    }
}

/** Label and a mono figure ("–" while measuring), read as one; the optional action is labelled with its row. */
@Composable
private fun FigureRow(
    label: String,
    figure: String?,
    action: String? = null,
    actionEnabled: Boolean = false,
    onAction: () -> Unit = {},
) {
    val c = Heycam.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.settingsRow)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = Heycam.type.label,
            color = c.ink,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Text(text = figure ?: NOT_MEASURED, style = Heycam.type.figureStrong, color = c.muted)
        if (action != null) {
            val description = stringResource(R.string.storage_action_a11y, action, label)
            TextButton(
                text = action,
                onClick = onAction,
                enabled = actionEnabled,
                color = c.ink,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .semantics { contentDescription = description },
            )
        }
    }
}

private const val NOT_MEASURED = "–"
