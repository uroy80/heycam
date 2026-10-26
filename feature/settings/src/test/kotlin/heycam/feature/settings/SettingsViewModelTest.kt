package heycam.feature.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.data.StorageUsage
import heycam.core.model.ExportOptions
import heycam.core.model.Margin
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.PageFilter
import heycam.core.model.PageSize
import heycam.core.model.Settings
import heycam.core.model.SizePreset
import heycam.core.model.ThemeSetting
import heycam.core.model.UnsupportedReason
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class SettingsViewModelTest {
    private val settings = FakeSettingsRepository(Settings(theme = ThemeSetting.NIGHT_INK, ocrScript = OcrScript.KOREAN))
    private val files = FakeFileStore(StorageUsage(pagesBytes = 10_000, trashBytes = 2_000, cacheBytes = 300))
    private val models = FakeTextModels()
    private val log = RecordingLog()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The view model and a collector that keeps its WhileSubscribed state alive. */
    private fun TestScope.viewModel(): SettingsViewModel {
        val vm = SettingsViewModel(settings, files, models, log)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun showsTheStoredSettings() = runTest {
        val state = viewModel().uiState.value
        assertThat(state.settings?.theme).isEqualTo(ThemeSetting.NIGHT_INK)
        assertThat(state.settings?.ocrScript).isEqualTo(OcrScript.KOREAN)
        assertThat(state.models).isEqualTo(ModelsDownload.Idle)
    }

    @Test
    fun everyChoiceIsSaved() = runTest {
        val vm = viewModel()
        vm.setTheme(ThemeSetting.DAY_PAPER)
        vm.setOcrScript(null)
        vm.setAutoText(false)
        vm.setDefaultFilter(PageFilter.BLACK_WHITE)
        vm.setExportSize(SizePreset.SMALL)
        vm.setExportPageSize(PageSize.LETTER)
        vm.setExportMargin(Margin.NORMAL)
        vm.setExportSearchable(false)
        advanceUntilIdle()
        assertThat(settings.current).isEqualTo(
            Settings(
                theme = ThemeSetting.DAY_PAPER,
                ocrScript = null,
                autoText = false,
                defaultFilter = PageFilter.BLACK_WHITE,
                exportDefaults = ExportOptions(size = SizePreset.SMALL, pageSize = PageSize.LETTER, margin = Margin.NORMAL, searchable = false),
            ),
        )
        assertThat(vm.uiState.value.settings).isEqualTo(settings.current)
    }

    @Test
    fun aFailedSaveIsReportedWithoutContent() = runTest {
        val vm = viewModel()
        settings.failure = IOException("/data/user/0/in.edgr.heycam/files/datastore/settings.preferences_pb")
        vm.messages.test {
            vm.setTheme(ThemeSetting.DAY_PAPER)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SettingsMessage.SaveFailed)
        }
        assertThat(log.lines).containsExactly("W/Settings settings not saved: IOException")
    }

    @Test
    fun spaceUsedCountsPagesTrashAndTemporaryFiles() = runTest {
        val vm = viewModel()
        assertThat(vm.uiState.value.spaceUsedBytes).isNull()
        vm.refreshSpaceUsed()
        advanceUntilIdle()
        assertThat(vm.uiState.value.spaceUsedBytes).isEqualTo(12_300)
    }

    @Test
    fun spaceUsedStaysBlankWhenItCannotBeMeasured() = runTest {
        files.usageFailure = IOException("/data/user/0/in.edgr.heycam/files/pages")
        val vm = viewModel()
        vm.refreshSpaceUsed()
        advanceUntilIdle()
        assertThat(vm.uiState.value.spaceUsedBytes).isNull()
        assertThat(log.lines).containsExactly("W/Settings usage unavailable: IOException")
    }

    @Test
    fun modelDownloadAsksForEveryScriptAndFollowsProgress() = runTest {
        val vm = viewModel()
        vm.downloadTextModels()
        advanceUntilIdle()
        assertThat(vm.uiState.value.models).isEqualTo(ModelsDownload.Running(percent = null))
        assertThat(models.requests.single()).containsExactlyElementsIn(OcrScript.entries)

        models.states.send(ModuleState.Downloading(bytesDownloaded = 1_500_000, totalBytes = 6_000_000))
        advanceUntilIdle()
        assertThat(vm.uiState.value.models).isEqualTo(ModelsDownload.Running(percent = 25))

        models.states.send(ModuleState.Downloading(bytesDownloaded = 10, totalBytes = null))
        advanceUntilIdle()
        assertThat(vm.uiState.value.models).isEqualTo(ModelsDownload.Running(percent = null))

        models.states.send(ModuleState.Ready)
        models.states.close()
        advanceUntilIdle()
        assertThat(vm.uiState.value.models).isEqualTo(ModelsDownload.Ready)
    }

    @Test
    fun aSecondTapWhileDownloadingIsIgnored() = runTest {
        val vm = viewModel()
        vm.downloadTextModels()
        advanceUntilIdle()
        vm.downloadTextModels()
        advanceUntilIdle()
        assertThat(models.requests).hasSize(1)
    }

    @Test
    fun offlineFailureCanBeRetried() = runTest {
        val vm = viewModel()
        vm.downloadTextModels()
        models.states.send(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
        models.states.close()
        advanceUntilIdle()
        assertThat(vm.uiState.value.models).isEqualTo(ModelsDownload.Failed(offline = true))

        models.reset()
        vm.downloadTextModels()
        models.states.send(ModuleState.Failed(ModuleFailure.UNKNOWN, retryable = true))
        models.states.close()
        advanceUntilIdle()
        assertThat(models.requests).hasSize(2)
        assertThat(vm.uiState.value.models).isEqualTo(ModelsDownload.Failed(offline = false))
    }

    @Test
    fun phonesWithoutPlayServicesAreTold() = runTest {
        val vm = viewModel()
        vm.downloadTextModels()
        models.states.send(ModuleState.Unsupported(UnsupportedReason.NO_PLAY_SERVICES))
        models.states.close()
        advanceUntilIdle()
        assertThat(vm.uiState.value.models).isEqualTo(ModelsDownload.Unsupported)
    }

    @Test
    fun aFlowThatEndsEarlyGoesBackToIdle() = runTest {
        val vm = viewModel()
        vm.downloadTextModels()
        models.states.send(ModuleState.Checking)
        models.states.close()
        advanceUntilIdle()
        assertThat(vm.uiState.value.models).isEqualTo(ModelsDownload.Idle)
    }

    @Test
    fun aCrashInTheDownloadIsAFailure() = runTest {
        models.failure = IllegalStateException("Play services gone")
        val vm = viewModel()
        vm.downloadTextModels()
        advanceUntilIdle()
        assertThat(vm.uiState.value.models).isEqualTo(ModelsDownload.Failed(offline = false))
        assertThat(log.lines).containsExactly("W/Settings model download failed: IllegalStateException")
    }
}
