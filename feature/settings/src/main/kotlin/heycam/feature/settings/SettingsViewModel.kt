package heycam.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.common.HeycamLog
import heycam.core.data.FileStore
import heycam.core.data.SettingsRepository
import heycam.core.domain.TextModels
import heycam.core.model.Margin
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.PageFilter
import heycam.core.model.PageSize
import heycam.core.model.Settings
import heycam.core.model.SizePreset
import heycam.core.model.ThemeSetting
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "Download all languages" as the row shows it. */
sealed interface ModelsDownload {
    data object Idle : ModelsDownload
    /** [percent] is null while Play services has not reported a size yet. */
    data class Running(val percent: Int?) : ModelsDownload
    data object Ready : ModelsDownload
    data class Failed(val offline: Boolean) : ModelsDownload
    data object Unsupported : ModelsDownload
}

data class SettingsUiState(
    /** Null until the stored settings have been read. */
    val settings: Settings? = null,
    /** Pages, trash and temporary files together; null until measured or when it could not be measured. */
    val spaceUsedBytes: Long? = null,
    val models: ModelsDownload = ModelsDownload.Idle,
)

sealed interface SettingsMessage {
    data object SaveFailed : SettingsMessage
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val fileStore: FileStore,
    private val textModels: TextModels,
    private val log: HeycamLog,
) : ViewModel() {

    private val spaceUsed = MutableStateFlow<Long?>(null)
    private val models = MutableStateFlow<ModelsDownload>(ModelsDownload.Idle)
    private var download: Job? = null

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    private val stored: Flow<Settings?> = settingsRepository.settings
        .map<Settings, Settings?> { it }
        .catch { e ->
            log.w(TAG, "settings unreadable: ${e.javaClass.simpleName}")
            emit(Settings())
        }

    val uiState: StateFlow<SettingsUiState> = combine(stored, spaceUsed, models) { settings, bytes, download ->
        SettingsUiState(settings = settings, spaceUsedBytes = bytes, models = download)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    fun setTheme(theme: ThemeSetting) = save { it.copy(theme = theme) }
    fun setOcrScript(script: OcrScript?) = save { it.copy(ocrScript = script) }
    fun setAutoText(on: Boolean) = save { it.copy(autoText = on) }
    fun setDefaultFilter(filter: PageFilter) = save { it.copy(defaultFilter = filter) }
    fun setExportSize(size: SizePreset) = save { it.copy(exportDefaults = it.exportDefaults.copy(size = size)) }
    fun setExportPageSize(pageSize: PageSize) = save { it.copy(exportDefaults = it.exportDefaults.copy(pageSize = pageSize)) }
    fun setExportMargin(margin: Margin) = save { it.copy(exportDefaults = it.exportDefaults.copy(margin = margin)) }
    fun setExportSearchable(on: Boolean) = save { it.copy(exportDefaults = it.exportDefaults.copy(searchable = on)) }

    /** Called whenever the screen starts, so the figure is fresh after Storage or Trash. */
    fun refreshSpaceUsed() {
        viewModelScope.launch {
            spaceUsed.value = try {
                fileStore.usage().let { it.pagesBytes + it.trashBytes + it.cacheBytes }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "usage unavailable: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    /** Every script, so text can be read offline later; Chinese, Japanese and Korean are otherwise fetched on first use. */
    fun downloadTextModels() {
        if (download?.isActive == true) return
        models.value = ModelsDownload.Running(percent = null)
        download = viewModelScope.launch {
            try {
                textModels.download(OcrScript.entries.toSet()).collect { state -> models.value = state.toDownload() }
                if (models.value is ModelsDownload.Running) models.value = ModelsDownload.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "model download failed: ${e.javaClass.simpleName}")
                models.value = ModelsDownload.Failed(offline = false)
            }
        }
    }

    private fun save(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            try {
                settingsRepository.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "settings not saved: ${e.javaClass.simpleName}")
                _messages.send(SettingsMessage.SaveFailed)
            }
        }
    }

    private fun ModuleState.toDownload(): ModelsDownload = when (this) {
        ModuleState.Ready -> ModelsDownload.Ready
        ModuleState.Checking, ModuleState.Installing -> ModelsDownload.Running(percent = null)
        is ModuleState.Downloading -> ModelsDownload.Running(
            percent = totalBytes?.takeIf { it > 0 }?.let { total -> (bytesDownloaded * 100 / total).toInt().coerceIn(0, 100) },
        )
        is ModuleState.Failed -> ModelsDownload.Failed(offline = reason == ModuleFailure.NO_NETWORK)
        is ModuleState.Unsupported -> ModelsDownload.Unsupported
    }

    private companion object {
        const val TAG = "Settings"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
