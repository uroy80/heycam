package heycam.feature.export

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.HeycamDialog
import heycam.core.designsystem.HeycamIcons
import heycam.core.designsystem.HeycamSize
import heycam.core.designsystem.HeycamTextField
import heycam.core.designsystem.Kicker
import heycam.core.designsystem.LoadBar
import heycam.core.designsystem.PrimaryButton
import heycam.core.designsystem.PrintedSwitchRow
import heycam.core.designsystem.SecondaryButton
import heycam.core.designsystem.SegmentedRow
import heycam.core.designsystem.StatusBanner
import heycam.core.designsystem.TextButton
import heycam.core.designsystem.focusRing
import heycam.core.model.ExportFormat
import heycam.core.model.Margin
import heycam.core.model.PageSize
import heycam.core.model.SizePreset
import kotlinx.coroutines.launch

/** System "Save to" picker for one file; the MIME type follows the chosen format. */
internal class CreateFile : ActivityResultContract<CreateFile.Input, Uri?>() {
    data class Input(val mime: String, val name: String)

    override fun createIntent(context: Context, input: Input): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

@Composable
internal fun ExportSheetRoute(args: ExportArgs, onDismiss: () -> Unit) {
    val viewModel = hiltViewModel<ExportViewModel, ExportViewModel.Factory>(
        creationCallback = { factory -> factory.create(args) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val fileLauncher = rememberLauncherForActivityResult(CreateFile()) { viewModel.onFilePicked(it) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onFolderPicked(it)
    }
    val dismiss by rememberUpdatedState(onDismiss)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                try {
                    when (event) {
                        is ExportEvent.PickFile -> fileLauncher.launch(CreateFile.Input(event.mime, event.suggestedName))
                        ExportEvent.PickFolder -> folderLauncher.launch(null)
                        is ExportEvent.Share -> context.startActivity(event.chooser)
                        ExportEvent.Print ->
                            if (activity != null) scope.launch { viewModel.printNow(activity) } else viewModel.onNoApp()
                        is ExportEvent.Saved -> {
                            val text = resources.getQuantityString(R.plurals.export_saved, event.files, event.files)
                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                            dismiss()
                        }
                    }
                } catch (e: ActivityNotFoundException) {
                    viewModel.onNoApp()
                }
            }
        }
    }
    ExportSheet(
        state = state,
        onFormat = viewModel::setFormat,
        onSize = viewModel::setSize,
        onPageSize = viewModel::setPageSize,
        onMargin = viewModel::setMargin,
        onSearchable = viewModel::setSearchable,
        onFileName = viewModel::setFileName,
        onSave = viewModel::save,
        onShare = viewModel::share,
        onPrint = viewModel::print,
        onTextPendingAnswer = viewModel::answerTextPending,
        onTextPendingDismiss = viewModel::dismissTextPending,
        onDismiss = onDismiss,
    )
}

/** Printed in the size cells while a preset is measured, and when it could not be measured. */
private const val MEASURING = "…"
private const val UNKNOWN_SIZE = "–"

private val FormatOrder = listOf(ExportFormat.PDF, ExportFormat.JPEG, ExportFormat.PNG, ExportFormat.TEXT)
private val SizeOrder = listOf(SizePreset.SMALL, SizePreset.STANDARD, SizePreset.ORIGINAL)
private val PageSizeOrder = listOf(PageSize.A4, PageSize.LETTER, PageSize.LEGAL, PageSize.FIT)
private val MarginOrder = listOf(Margin.NONE, Margin.NARROW, Margin.NORMAL)

/**
 * The export sheet (S11): square, paper, a 3 dp ink rule on top, over a scrim that closes it. Stateless; the
 * route above feeds it from [ExportViewModel]. While an export runs, neither the scrim nor Back closes the sheet,
 * so a stray tap never cancels it; the handle's "Close" still does.
 */
@Composable
internal fun ExportSheet(
    state: ExportUiState,
    onFormat: (ExportFormat) -> Unit,
    onSize: (SizePreset) -> Unit,
    onPageSize: (PageSize) -> Unit,
    onMargin: (Margin) -> Unit,
    onSearchable: (Boolean) -> Unit,
    onFileName: (String) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onTextPendingAnswer: (wait: Boolean) -> Unit,
    onTextPendingDismiss: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Heycam.colors
    val running by rememberUpdatedState(state.running)
    val dismiss by rememberUpdatedState(onDismiss)
    BackHandler(enabled = state.running) {}
    Box(Modifier.fillMaxSize()) {
        // Tapping outside closes, as with any sheet. A gesture rather than a clickable, so it adds no unlabelled
        // node for TalkBack, which closes the sheet with the handle's "Close".
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { if (!running) dismiss() } })
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = HeycamSize.maxContentWidth)
                    .fillMaxWidth()
                    .background(c.paper)
                    .drawBehind {
                        val rule = HeycamSize.ruleThick.toPx()
                        drawLine(c.ink, Offset(0f, rule / 2), Offset(size.width, rule / 2), rule)
                    }
                    .pointerInput(Unit) { detectTapGestures { } } // taps on the sheet never reach the scrim
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .verticalScroll(rememberScrollState())
                    .padding(start = HeycamSize.gutter, end = HeycamSize.gutter, bottom = HeycamSize.gutter),
            ) {
                SheetHandle(onDismiss)
                Kicker(stringResource(R.string.export_kicker))
                Text(
                    text = if (state.documents.size > 1) {
                        pluralStringResource(R.plurals.export_title_documents, state.documents.size, state.documents.size)
                    } else {
                        pluralStringResource(R.plurals.export_title_pages, state.pageCount, state.pageCount)
                    },
                    style = Heycam.type.headline.copy(fontSize = 24.sp, lineHeight = 28.sp),
                    color = c.ink,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                )
                when {
                    state.loading -> LoadBar(Modifier.padding(vertical = 24.dp))
                    state.unavailable -> Unavailable(onDismiss)
                    else -> ExportForm(state, onFormat, onSize, onPageSize, onMargin, onSearchable, onFileName, onSave, onShare, onPrint)
                }
            }
        }
    }
    state.textPendingPages?.let { pages ->
        // Back and a tap outside only close the question; each answer is its own button, side by side.
        HeycamDialog(
            title = pluralStringResource(R.plurals.export_text_pending, pages, pages),
            confirmLabel = stringResource(R.string.export_text_wait),
            onConfirm = { onTextPendingAnswer(true) },
            onDismissRequest = onTextPendingDismiss,
            dismissLabel = stringResource(R.string.export_text_skip),
            onDismiss = { onTextPendingAnswer(false) },
        )
    }
}

/** The 32 x 4 dp ink handle in a 48 dp target: TalkBack's "Close". */
@Composable
private fun SheetHandle(onDismiss: () -> Unit) {
    val close = stringResource(R.string.export_close)
    Box(
        Modifier
            .fillMaxWidth()
            .height(HeycamSize.touch),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(HeycamSize.touch)
                .focusRing()
                .clickable(onClickLabel = close, role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = close },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).background(Heycam.colors.ink))
        }
    }
}

@Composable
private fun Unavailable(onDismiss: () -> Unit) {
    Text(
        text = stringResource(R.string.export_unavailable),
        style = Heycam.type.body,
        color = Heycam.colors.ink,
        modifier = Modifier.padding(vertical = 16.dp),
    )
    SecondaryButton(stringResource(R.string.export_close), onDismiss, Modifier.fillMaxWidth())
}

/** Small label above a control group (mockup ".lbl"); a heading for TalkBack navigation. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = Heycam.type.label.copy(fontSize = 13.sp),
        color = Heycam.colors.ink2,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun ColumnScope.ExportForm(
    state: ExportUiState,
    onFormat: (ExportFormat) -> Unit,
    onSize: (SizePreset) -> Unit,
    onPageSize: (PageSize) -> Unit,
    onMargin: (Margin) -> Unit,
    onSearchable: (Boolean) -> Unit,
    onFileName: (String) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
) {
    val context = LocalContext.current
    val editable = !state.running

    GroupLabel(stringResource(R.string.export_format))
    SegmentedRow(
        options = FormatOrder.map { stringResource(it.labelRes) },
        selected = FormatOrder.indexOf(state.format),
        onSelect = { onFormat(FormatOrder[it]) },
        enabled = editable,
    )

    if (state.format != ExportFormat.TEXT) {
        GroupLabel(stringResource(R.string.export_size))
        val sizes = SizeOrder.map { preset -> state.estimates[preset]?.let { Formatter.formatShortFileSize(context, it) } }
        SegmentedRow(
            options = SizeOrder.map { stringResource(it.labelRes) },
            details = SizeOrder.mapIndexed { i, preset ->
                when {
                    preset !in state.estimates -> MEASURING
                    else -> sizes[i] ?: UNKNOWN_SIZE
                }
            },
            selected = SizeOrder.indexOf(state.size),
            onSelect = { onSize(SizeOrder[it]) },
            enabled = editable,
            // TalkBack hears "Small, about 600 kB", never the "…" placeholder.
            spoken = SizeOrder.mapIndexed { i, preset ->
                val label = stringResource(preset.labelRes)
                sizes[i]?.let { stringResource(R.string.export_size_spoken, label, it) } ?: label
            },
        )
    }

    if (state.format == ExportFormat.PDF) {
        GroupLabel(stringResource(R.string.export_page_size))
        SegmentedRow(
            options = PageSizeOrder.map { stringResource(it.labelRes) },
            selected = PageSizeOrder.indexOf(state.pageSize),
            onSelect = { onPageSize(PageSizeOrder[it]) },
            enabled = editable,
        )
        GroupLabel(stringResource(R.string.export_margin))
        SegmentedRow(
            options = MarginOrder.map { stringResource(it.labelRes) },
            selected = MarginOrder.indexOf(state.margin),
            onSelect = { onMargin(MarginOrder[it]) },
            enabled = editable,
        )
        Spacer(Modifier.height(8.dp))
        PrintedSwitchRow(
            checked = state.searchable,
            onCheckedChange = onSearchable,
            label = stringResource(R.string.export_searchable),
            enabled = editable,
        )
    }

    if (state.singleDocument) {
        HeycamTextField(
            value = state.fileName,
            onValueChange = onFileName,
            label = stringResource(R.string.export_file_name),
            suffix = state.format.extension,
            mono = true,
            enabled = editable,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        SecondaryButton(
            text = stringResource(R.string.export_save),
            onClick = onSave,
            icon = HeycamIcons.Save,
            enabled = state.canExport,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            text = stringResource(R.string.export_share),
            onClick = onShare,
            icon = HeycamIcons.Share,
            enabled = state.canExport,
            modifier = Modifier.weight(1.3f),
        )
    }
    TextButton(
        text = stringResource(R.string.export_print),
        onClick = onPrint,
        enabled = state.canExport,
        color = Heycam.colors.ink,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
    )

    Status(state)
}

@Composable
private fun Status(state: ExportUiState) {
    val error = state.error
    when {
        state.running -> {
            val progress = state.progress?.takeIf { it.total > 0 }
            StatusBanner(
                text = progress?.let { stringResource(R.string.export_progress, it.done, it.total) }
                    ?: stringResource(R.string.export_running),
                busy = progress == null,
                progress = progress?.let { it.done.toFloat() / it.total },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        error != null -> StatusBanner(text = stringResource(error.messageRes), isError = true, modifier = Modifier.padding(top = 12.dp))
    }
}

private val ExportFormat.labelRes: Int
    get() = when (this) {
        ExportFormat.PDF -> R.string.export_format_pdf
        ExportFormat.JPEG -> R.string.export_format_jpeg
        ExportFormat.PNG -> R.string.export_format_png
        ExportFormat.TEXT -> R.string.export_format_text
    }

private val SizePreset.labelRes: Int
    get() = when (this) {
        SizePreset.SMALL -> R.string.export_size_small
        SizePreset.STANDARD -> R.string.export_size_standard
        SizePreset.ORIGINAL -> R.string.export_size_original
    }

private val PageSize.labelRes: Int
    get() = when (this) {
        PageSize.A4 -> R.string.export_page_a4
        PageSize.LETTER -> R.string.export_page_letter
        PageSize.LEGAL -> R.string.export_page_legal
        PageSize.FIT -> R.string.export_page_fit
    }

private val Margin.labelRes: Int
    get() = when (this) {
        Margin.NONE -> R.string.export_margin_none
        Margin.NARROW -> R.string.export_margin_narrow
        Margin.NORMAL -> R.string.export_margin_normal
    }

private val ExportError.messageRes: Int
    get() = when (this) {
        ExportError.NO_SPACE -> R.string.export_error_no_space
        ExportError.WRITE_FAILED -> R.string.export_error_write
        ExportError.SOURCE_MISSING -> R.string.export_error_missing
        ExportError.PRINT_FAILED -> R.string.export_error_print
        ExportError.NO_APP -> R.string.export_error_no_app
    }
