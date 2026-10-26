package heycam.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.common.HeycamLog
import heycam.core.data.SettingsRepository
import heycam.core.designsystem.ThemeMode
import heycam.core.model.ThemeSetting
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository, log: HeycamLog) : ViewModel() {
    /** Null until settings are read; the splash screen stays up until then so the first frame has the right theme. */
    val theme: StateFlow<ThemeMode?> = settings.settings
        .map { it.theme.toThemeMode() }
        .catch { error ->
            log.w(TAG, "Settings unreadable, using the system theme: ${error.javaClass.simpleName}")
            emit(ThemeMode.System)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    private val pendingLaunch = MutableStateFlow<LaunchAction?>(null)

    /** The latest entry-point request, kept across configuration changes until the UI has acted on it. */
    val launchAction: StateFlow<LaunchAction?> = pendingLaunch.asStateFlow()

    fun onLaunch(action: LaunchAction) {
        pendingLaunch.value = action
    }

    fun onLaunchHandled(action: LaunchAction) {
        pendingLaunch.compareAndSet(action, null)
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}

internal fun ThemeSetting.toThemeMode(): ThemeMode = when (this) {
    ThemeSetting.SYSTEM -> ThemeMode.System
    ThemeSetting.DAY_PAPER -> ThemeMode.DayPaper
    ThemeSetting.NIGHT_INK -> ThemeMode.NightInk
}
