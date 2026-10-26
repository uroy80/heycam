package heycam.app

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import heycam.core.designsystem.applyBroadsheetSystemBars

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash stays until the settings are read, so the first frame is already in the chosen edition.
        installSplashScreen().setKeepOnScreenCondition { viewModel.theme.value == null }
        super.onCreate(savedInstanceState)
        applyBroadsheetSystemBars(dark = systemIsDark())
        // A recreated activity handled its launch intent the first time round.
        if (savedInstanceState == null) LaunchAction.from(intent)?.let(viewModel::onLaunch)

        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            theme?.let { mode ->
                LaunchedEffect(mode) { shareEditionWithSystem(mode) }
                HeycamTheme(mode) {
                    val dark = mode.isDark()
                    LaunchedEffect(dark) { applyBroadsheetSystemBars(dark) }
                    val launchAction by viewModel.launchAction.collectAsStateWithLifecycle()
                    HeycamApp(launchAction = launchAction, onLaunchHandled = viewModel::onLaunchHandled)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LaunchAction.from(intent)?.let(viewModel::onLaunch)
    }

    private fun systemIsDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * Android 12+ draws the splash screen before heycam runs. Telling the system the chosen edition makes the next
     * splash (and the window behind it) Day paper or Night ink to match. The system persists it, so this only changes
     * anything when the setting does.
     */
    private fun shareEditionWithSystem(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val nightMode = when (mode) {
            ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.DayPaper -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.NightInk -> UiModeManager.MODE_NIGHT_YES
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(nightMode)
    }
}

@Composable
private fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.DayPaper -> false
    ThemeMode.NightInk -> true
}
