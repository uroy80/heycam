package heycam.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
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
import heycam.core.designsystem.HeycamIcons
import heycam.core.designsystem.HeycamSize
import heycam.core.designsystem.HeycamSnackbarHost
import heycam.core.designsystem.LoadBar
import heycam.core.designsystem.PrintedSwitchRow
import heycam.core.designsystem.SegmentedRow
import heycam.core.designsystem.TextButton
import heycam.core.designsystem.focusRing
import heycam.core.designsystem.showHeycamSnackbar
import heycam.core.model.ExportOptions
import heycam.core.model.Margin
import heycam.core.model.OcrScript
import heycam.core.model.PageFilter
import heycam.core.model.PageSize
import heycam.core.model.Settings
import heycam.core.model.SizePreset
import heycam.core.model.ThemeSetting

@Composable
internal fun SettingsRouteScreen(
    onBack: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val saveFailed = stringResource(R.string.settings_save_failed)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.messages.collect { message ->
                when (message) {
                    SettingsMessage.SaveFailed -> snackbar.showHeycamSnackbar(saveFailed, isError = true)
                }
            }
        }
    }
    LifecycleStartEffect(viewModel) {
        viewModel.refreshSpaceUsed()
        onStopOrDispose { }
    }
    SettingsScreen(
        state = state,
        actions = SettingsActions(
            onBack = onBack,
            onTheme = viewModel::setTheme,
            onOcrScript = viewModel::setOcrScript,
            onAutoText = viewModel::setAutoText,
            onDefaultFilter = viewModel::setDefaultFilter,
            onDownloadModels = viewModel::downloadTextModels,
            onExportSize = viewModel::setExportSize,
            onExportPageSize = viewModel::setExportPageSize,
            onExportMargin = viewModel::setExportMargin,
            onExportSearchable = viewModel::setExportSearchable,
            onOpenStorage = onOpenStorage,
            onOpenAbout = onOpenAbout,
        ),
        snackbar = snackbar,
    )
}

internal class SettingsActions(
    val onBack: () -> Unit,
    val onTheme: (ThemeSetting) -> Unit,
    val onOcrScript: (OcrScript?) -> Unit,
    val onAutoText: (Boolean) -> Unit,
    val onDefaultFilter: (PageFilter) -> Unit,
    val onDownloadModels: () -> Unit,
    val onExportSize: (SizePreset) -> Unit,
    val onExportPageSize: (PageSize) -> Unit,
    val onExportMargin: (Margin) -> Unit,
    val onExportSearchable: (Boolean) -> Unit,
    val onOpenStorage: () -> Unit,
    val onOpenAbout: () -> Unit,
)

/** Tags the download glyph of the idle "Download all languages" row. */
internal const val MODELS_DOWNLOAD_ICON = "settings:models-download"

private val ThemeOrder = listOf(ThemeSetting.SYSTEM, ThemeSetting.DAY_PAPER, ThemeSetting.NIGHT_INK)
/** MVP filters only; Whiteboard arrives in v1.1 (PLAN D13). */
private val FilterOrder = listOf(PageFilter.ORIGINAL, PageFilter.AUTO, PageFilter.GRAYSCALE, PageFilter.BLACK_WHITE)
private val SizeOrder = listOf(SizePreset.SMALL, SizePreset.STANDARD, SizePreset.ORIGINAL)
private val PageSizeOrder = listOf(PageSize.A4, PageSize.LETTER, PageSize.LEGAL, PageSize.FIT)
private val MarginOrder = listOf(Margin.NONE, Margin.NARROW, Margin.NORMAL)
/** Auto (null) first, then every script ML Kit reads. */
private val ScriptOrder: List<OcrScript?> = listOf(null) + OcrScript.entries

/** Settings (S13). The lock rows of the mockup arrive with the v1.1 app lock. */
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    snackbar: SnackbarHostState,
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    var choosingScript by rememberSaveable { mutableStateOf(false) }
    var showingData by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        SettingsPage(title = stringResource(R.string.settings_title), onBack = actions.onBack, windowInsets = windowInsets) {
            val settings = state.settings
            if (settings == null) {
                LoadBar(Modifier.padding(vertical = 24.dp))
            } else {
                Appearance(settings, actions)
                Scanning(settings, state.models, actions, onChooseScript = { choosingScript = true })
                ExportDefaults(settings.exportDefaults, actions)
                Privacy(onShowData = { showingData = true })
                Storage(state.spaceUsedBytes, actions)
                SectionHead(stringResource(R.string.settings_about_group))
                LinkRow(label = stringResource(R.string.settings_about), onClick = actions.onOpenAbout)
            }
        }
        HeycamSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        )
    }
    val settings = state.settings
    if (choosingScript && settings != null) {
        ScriptDialog(
            selected = settings.ocrScript,
            onSelect = {
                choosingScript = false
                actions.onOcrScript(it)
            },
            onDismiss = { choosingScript = false },
        )
    }
    if (showingData) YourDataDialog(onDismiss = { showingData = false })
}

@Composable
private fun Appearance(settings: Settings, actions: SettingsActions) {
    SectionHead(stringResource(R.string.settings_appearance))
    StackedRow(stringResource(R.string.settings_theme)) {
        SegmentedRow(
            options = ThemeOrder.map { stringResource(it.labelRes) },
            selected = ThemeOrder.indexOf(settings.theme),
            onSelect = { actions.onTheme(ThemeOrder[it]) },
        )
    }
}

@Composable
private fun Scanning(settings: Settings, models: ModelsDownload, actions: SettingsActions, onChooseScript: () -> Unit) {
    SectionHead(stringResource(R.string.settings_scanning))
    StackedRow(stringResource(R.string.settings_default_filter)) {
        SegmentedRow(
            options = FilterOrder.map { stringResource(it.labelRes) },
            selected = FilterOrder.indexOf(settings.defaultFilter),
            onSelect = { actions.onDefaultFilter(FilterOrder[it]) },
        )
    }
    RowDivider()
    PrintedSwitchRow(
        checked = settings.autoText,
        onCheckedChange = actions.onAutoText,
        label = stringResource(R.string.settings_auto_text),
    )
    RowDivider()
    LinkRow(
        label = stringResource(R.string.settings_script),
        value = stringResource(settings.ocrScript.labelRes),
        onClick = onChooseScript,
    )
    RowDivider()
    ModelsRow(models, actions.onDownloadModels)
}

@Composable
private fun ModelsRow(models: ModelsDownload, onDownload: () -> Unit) {
    val c = Heycam.colors
    val running = models is ModelsDownload.Running
    val percent = (models as? ModelsDownload.Running)?.percent
    val status = when (models) {
        ModelsDownload.Idle -> null
        is ModelsDownload.Running -> percent?.let { stringResource(R.string.settings_models_percent, it) }
            ?: stringResource(R.string.settings_models_running)
        ModelsDownload.Ready -> stringResource(R.string.settings_models_ready)
        is ModelsDownload.Failed ->
            stringResource(if (models.offline) R.string.settings_models_offline else R.string.settings_models_failed)
        ModelsDownload.Unsupported -> stringResource(R.string.settings_models_unsupported)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.settingsRow)
            .focusRing()
            .clickable(enabled = !running && models != ModelsDownload.Unsupported, role = Role.Button, onClick = onDownload)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_models),
                style = Heycam.type.label,
                color = c.ink,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            if (status != null) {
                Text(
                    text = status,
                    style = Heycam.type.figureStrong,
                    color = if (models is ModelsDownload.Failed) c.accent else c.muted,
                    // A percentage changes at every step and the bar below reports it; only the other states are read out.
                    modifier = if (percent == null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                )
            }
            if (models == ModelsDownload.Idle) {
                Icon(
                    imageVector = HeycamIcons.Save,
                    contentDescription = null,
                    tint = c.muted,
                    modifier = Modifier.size(20.dp).testTag(MODELS_DOWNLOAD_ICON),
                )
            }
        }
        if (running) LoadBar(Modifier.padding(top = 10.dp), progress = percent?.let { it / 100f })
    }
}

@Composable
private fun ExportDefaults(defaults: ExportOptions, actions: SettingsActions) {
    SectionHead(stringResource(R.string.settings_export))
    StackedRow(stringResource(R.string.settings_export_size)) {
        SegmentedRow(
            options = SizeOrder.map { stringResource(it.labelRes) },
            selected = SizeOrder.indexOf(defaults.size),
            onSelect = { actions.onExportSize(SizeOrder[it]) },
        )
    }
    StackedRow(stringResource(R.string.settings_export_page_size)) {
        SegmentedRow(
            options = PageSizeOrder.map { stringResource(it.labelRes) },
            selected = PageSizeOrder.indexOf(defaults.pageSize),
            onSelect = { actions.onExportPageSize(PageSizeOrder[it]) },
        )
    }
    StackedRow(stringResource(R.string.settings_export_margin)) {
        SegmentedRow(
            options = MarginOrder.map { stringResource(it.labelRes) },
            selected = MarginOrder.indexOf(defaults.margin),
            onSelect = { actions.onExportMargin(MarginOrder[it]) },
        )
    }
    RowDivider()
    PrintedSwitchRow(
        checked = defaults.searchable,
        onCheckedChange = actions.onExportSearchable,
        label = stringResource(R.string.settings_export_searchable),
    )
}

@Composable
private fun Privacy(onShowData: () -> Unit) {
    SectionHead(stringResource(R.string.settings_privacy))
    LinkRow(label = stringResource(R.string.settings_your_data), onClick = onShowData)
}

@Composable
private fun Storage(spaceUsedBytes: Long?, actions: SettingsActions) {
    val context = LocalContext.current
    SectionHead(stringResource(R.string.settings_storage))
    LinkRow(
        label = stringResource(R.string.settings_space_used),
        value = spaceUsedBytes?.let { Formatter.formatShortFileSize(context, it) },
        onClick = actions.onOpenStorage,
    )
}

/** Pick a script, then OK; the current one carries the check. */
@Composable
private fun ScriptDialog(selected: OcrScript?, onSelect: (OcrScript?) -> Unit, onDismiss: () -> Unit) {
    var choice by rememberSaveable(selected) { mutableStateOf(selected) }
    HeycamDialog(
        title = stringResource(R.string.settings_script),
        confirmLabel = stringResource(R.string.settings_ok),
        onConfirm = { onSelect(choice) },
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.settings_cancel),
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            ScriptOrder.forEach { script -> ScriptChoice(script, chosen = script == choice, onClick = { choice = script }) }
        }
    }
}

@Composable
private fun ScriptChoice(script: OcrScript?, chosen: Boolean, onClick: () -> Unit) {
    val c = Heycam.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.settingsRow)
            .focusRing()
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(script.labelRes), style = Heycam.type.label, color = c.ink)
            Text(stringResource(script.sampleRes), style = Heycam.type.hint, color = c.muted)
        }
        if (chosen) {
            Icon(HeycamIcons.Check, contentDescription = null, tint = c.ink, modifier = Modifier.size(HeycamSize.icon))
        } else {
            Spacer(Modifier.size(HeycamSize.icon))
        }
    }
}

/** PLAN F10 "How heycam handles your data": the plain statement and the policy link, nothing more. */
@Composable
private fun YourDataDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    HeycamDialog(
        title = stringResource(R.string.settings_your_data),
        text = stringResource(R.string.settings_your_data_statement),
        confirmLabel = stringResource(R.string.settings_ok),
        onConfirm = onDismiss,
        onDismissRequest = onDismiss,
        dismissLabel = null,
    ) {
        TextButton(stringResource(R.string.settings_privacy_policy), onClick = { openWebPage(context, PRIVACY_POLICY_URL) })
    }
}

private val ThemeSetting.labelRes: Int
    get() = when (this) {
        ThemeSetting.SYSTEM -> R.string.settings_theme_system
        ThemeSetting.DAY_PAPER -> R.string.settings_theme_day
        ThemeSetting.NIGHT_INK -> R.string.settings_theme_night
    }

private val PageFilter.labelRes: Int
    get() = when (this) {
        PageFilter.ORIGINAL -> R.string.settings_filter_original
        PageFilter.AUTO -> R.string.settings_filter_auto
        PageFilter.GRAYSCALE -> R.string.settings_filter_grey
        PageFilter.BLACK_WHITE -> R.string.settings_filter_bw
        PageFilter.WHITEBOARD -> R.string.settings_filter_whiteboard
    }

private val OcrScript?.labelRes: Int
    get() = when (this) {
        null -> R.string.settings_script_auto
        OcrScript.LATIN -> R.string.settings_script_latin
        OcrScript.DEVANAGARI -> R.string.settings_script_devanagari
        OcrScript.CHINESE -> R.string.settings_script_chinese
        OcrScript.JAPANESE -> R.string.settings_script_japanese
        OcrScript.KOREAN -> R.string.settings_script_korean
    }

private val OcrScript?.sampleRes: Int
    get() = when (this) {
        null -> R.string.settings_script_auto_sample
        OcrScript.LATIN -> R.string.settings_script_latin_sample
        OcrScript.DEVANAGARI -> R.string.settings_script_devanagari_sample
        OcrScript.CHINESE -> R.string.settings_script_chinese_sample
        OcrScript.JAPANESE -> R.string.settings_script_japanese_sample
        OcrScript.KOREAN -> R.string.settings_script_korean_sample
    }

private val SizePreset.labelRes: Int
    get() = when (this) {
        SizePreset.SMALL -> R.string.settings_size_small
        SizePreset.STANDARD -> R.string.settings_size_standard
        SizePreset.ORIGINAL -> R.string.settings_size_original
    }

private val PageSize.labelRes: Int
    get() = when (this) {
        PageSize.A4 -> R.string.settings_page_a4
        PageSize.LETTER -> R.string.settings_page_letter
        PageSize.LEGAL -> R.string.settings_page_legal
        PageSize.FIT -> R.string.settings_page_fit
    }

private val Margin.labelRes: Int
    get() = when (this) {
        Margin.NONE -> R.string.settings_margin_none
        Margin.NARROW -> R.string.settings_margin_narrow
        Margin.NORMAL -> R.string.settings_margin_normal
    }
