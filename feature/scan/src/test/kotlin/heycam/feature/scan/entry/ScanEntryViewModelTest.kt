package heycam.feature.scan.entry

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.model.DocumentId
import heycam.core.model.ImportTarget
import heycam.core.model.ModuleState
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.Settings
import heycam.core.model.UnsupportedReason
import heycam.core.scanner.ScanRequest
import heycam.core.scanner.ScanResult
import heycam.core.scanner.ScanStart
import heycam.feature.scan.FakeScanImporter
import heycam.feature.scan.FakeScanSessionStore
import heycam.feature.scan.FakeScannerLauncher
import heycam.feature.scan.FakeSettingsRepository
import heycam.feature.scan.MainDispatcherRule
import heycam.feature.scan.stagedImage
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ScanEntryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val launcher = FakeScannerLauncher()
    private val importer = FakeScanImporter()
    private val sessions = FakeScanSessionStore()
    private val settings = FakeSettingsRepository()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        ScanEntryViewModel(launcher, importer, sessions, settings, handle)

    @Test
    fun scanStagesPagesAndOpensReview() = runTest {
        val vm = viewModel()
        vm.events.test {
            vm.scan(ImportTarget.NewDocument(), ScanRequest())
            val start = awaitItem() as EntryEvent.StartScanner
            assertThat(vm.start(activity, start.request)).isNull() // the fake answers at once
            val staged = awaitItem() as EntryEvent.Staged
            val session = sessions.sessions.getValue(staged.sessionId)
            assertThat(session.target).isEqualTo(ImportTarget.NewDocument())
            assertThat(session.pages.map { it.image.file.name }).containsExactly("scan1.jpg")
        }
    }

    @Test
    fun defaultFilterIsAppliedToNewPages() = runTest {
        val custom = FakeSettingsRepository(Settings(defaultFilter = PageFilter.AUTO))
        val vm = ScanEntryViewModel(launcher, importer, sessions, custom, SavedStateHandle())
        vm.events.test {
            vm.scan(ImportTarget.NewDocument(), ScanRequest())
            vm.start(activity, (awaitItem() as EntryEvent.StartScanner).request)
            val staged = awaitItem() as EntryEvent.Staged
            assertThat(sessions.sessions.getValue(staged.sessionId).pages.map { it.edits.filter }).containsExactly(PageFilter.AUTO)
        }
    }

    @Test
    fun missingScannerDownloadsThenStartsByItself() = runTest {
        launcher.availability = ModuleState.Downloading(0, null)
        val vm = viewModel()
        vm.events.test {
            vm.scan(ImportTarget.NewDocument(), ScanRequest())
            val first = awaitItem() as EntryEvent.StartScanner
            assertThat(vm.start(activity, first.request)).isNull()
            assertThat(launcher.installs).isEqualTo(1)
            // Ready arrives from install(): the pending scan is asked for again.
            assertThat(awaitItem()).isEqualTo(EntryEvent.StartScanner(ScanRequest()))
            assertThat(vm.scanner.value).isNull()
        }
    }

    @Test
    fun failedDownloadOffersRetry() = runTest {
        launcher.availability = ModuleState.Checking
        launcher.installStates = listOf(ModuleState.Downloading(10, 100), ModuleState.Failed(heycam.core.model.ModuleFailure.NO_NETWORK, retryable = true))
        val vm = viewModel()
        vm.scan(ImportTarget.NewDocument(), ScanRequest())
        vm.start(activity, ScanRequest())
        assertThat(vm.scanner.value).isEqualTo(ModuleState.Failed(heycam.core.model.ModuleFailure.NO_NETWORK, retryable = true))
        vm.dismissScanner()
        assertThat(vm.scanner.value).isNull()
    }

    @Test
    fun unsupportedPhoneSaysSo() = runTest {
        launcher.availability = ModuleState.Unsupported(UnsupportedReason.LOW_RAM)
        val vm = viewModel()
        vm.events.test {
            vm.scan(ImportTarget.NewDocument(), ScanRequest())
            vm.start(activity, (awaitItem() as EntryEvent.StartScanner).request)
            assertThat(awaitItem()).isEqualTo(EntryEvent.Notice(EntryNotice.Unavailable(UnsupportedReason.LOW_RAM)))
            assertThat(vm.scanner.value).isEqualTo(ModuleState.Unsupported(UnsupportedReason.LOW_RAM))
        }
        assertThat(launcher.installs).isEqualTo(0)
    }

    @Test
    fun cancelledScanCreatesNothing() = runTest {
        launcher.start = ScanStart.Immediate(ScanResult.Cancelled)
        val vm = viewModel()
        vm.events.test {
            vm.scan(ImportTarget.NewDocument(), ScanRequest())
            vm.start(activity, (awaitItem() as EntryEvent.StartScanner).request)
            expectNoEvents()
        }
        assertThat(sessions.sessions).isEmpty()
    }

    @Test
    fun scannerLaunchBlocksASecondTapUntilTheResult() = runTest {
        val pending = PendingIntent.getActivity(ApplicationProvider.getApplicationContext(), 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        launcher.start = ScanStart.Launch(IntentSenderRequest.Builder(pending).build())
        launcher.finish = ScanResult.Pages(listOf(stagedImage("a"), stagedImage("b")))
        val vm = viewModel()
        vm.events.test {
            vm.scan(ImportTarget.AppendTo(DocumentId(7)), ScanRequest())
            assertThat(vm.start(activity, (awaitItem() as EntryEvent.StartScanner).request)).isNotNull()
            vm.scan(ImportTarget.NewDocument(), ScanRequest())
            expectNoEvents()
            vm.onScannerResult(Activity.RESULT_OK, null)
            val staged = awaitItem() as EntryEvent.Staged
            val session = sessions.sessions.getValue(staged.sessionId)
            assertThat(session.target).isEqualTo(ImportTarget.AppendTo(DocumentId(7)))
            assertThat(session.pages).hasSize(2)
        }
    }

    @Test
    fun pendingTargetSurvivesProcessDeathWhileScannerIsOpen() = runTest {
        val pending = PendingIntent.getActivity(ApplicationProvider.getApplicationContext(), 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        launcher.start = ScanStart.Launch(IntentSenderRequest.Builder(pending).build())
        launcher.finish = ScanResult.Pages(listOf(stagedImage("c")))
        val handle = SavedStateHandle()
        viewModel(handle).apply {
            scan(ImportTarget.Replace(PageId(9)), ScanRequest(pageLimit = 1))
            start(activity, ScanRequest(pageLimit = 1))
        }
        val restored = viewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        restored.events.test {
            restored.onScannerResult(Activity.RESULT_OK, null)
            val staged = awaitItem() as EntryEvent.Staged
            assertThat(sessions.sessions.getValue(staged.sessionId).target).isEqualTo(ImportTarget.Replace(PageId(9)))
        }
        assertThat(launcher.requests.single().pageLimit).isEqualTo(1)
    }

    @Test
    fun retakeKeepsOnlyOnePage() = runTest {
        launcher.start = ScanStart.Immediate(ScanResult.Pages(listOf(stagedImage("x"), stagedImage("y"))))
        val vm = viewModel()
        vm.events.test {
            vm.scan(ImportTarget.Replace(PageId(3)), ScanRequest(pageLimit = 1))
            vm.start(activity, (awaitItem() as EntryEvent.StartScanner).request)
            val staged = awaitItem() as EntryEvent.Staged
            assertThat(sessions.sessions.getValue(staged.sessionId).pages).hasSize(1)
        }
    }

    @Test
    fun scannerFailureIsReported() = runTest {
        launcher.start = ScanStart.Immediate(ScanResult.Failed("IllegalStateException"))
        val vm = viewModel()
        vm.events.test {
            vm.scan(ImportTarget.NewDocument(), ScanRequest())
            vm.start(activity, (awaitItem() as EntryEvent.StartScanner).request)
            assertThat(awaitItem()).isEqualTo(EntryEvent.Notice(EntryNotice.Failed))
        }
    }

    @Test
    fun photosAreStagedFromThePicker() = runTest {
        val vm = viewModel()
        vm.events.test {
            vm.importPhotos(ImportTarget.NewDocument())
            assertThat(awaitItem()).isEqualTo(EntryEvent.PickPhotos)
            vm.onPickerOpened()
            vm.onPhotosPicked(listOf(Uri.parse("content://media/1"), Uri.parse("content://media/2")))
            assertThat(awaitItem()).isInstanceOf(EntryEvent.Staged::class.java)
        }
        assertThat(importer.staged.single().second).isEqualTo(PageOrigin.PHOTO_PICKER)
        assertThat(importer.staged.single().first).hasSize(2)
    }

    @Test
    fun pickerCancelledLeavesNothingBehind() = runTest {
        val vm = viewModel()
        vm.events.test {
            vm.importPhotos(ImportTarget.NewDocument())
            awaitItem()
            vm.onPickerOpened()
            vm.onPhotosPicked(emptyList())
            expectNoEvents()
            // Not blocked: the next import asks for the picker again.
            vm.importPhotos(ImportTarget.NewDocument())
            assertThat(awaitItem()).isEqualTo(EntryEvent.PickPhotos)
        }
        assertThat(importer.staged).isEmpty()
    }

    @Test
    fun sharedFilesOnlyAcceptContentUris() = runTest {
        importer.stageResult = heycam.core.domain.StageResult(listOf(stagedImage("shared", PageOrigin.SHARE)), rejected = 1)
        val vm = viewModel()
        vm.events.test {
            vm.importShared(listOf(Uri.parse("content://x/1"), Uri.parse("file:///sdcard/a.jpg"), Uri.parse("content://x/2")))
            assertThat(awaitItem()).isEqualTo(EntryEvent.Notice(EntryNotice.Rejected(2)))
            val staged = awaitItem() as EntryEvent.Staged
            assertThat(sessions.sessions.getValue(staged.sessionId).target).isEqualTo(ImportTarget.NewDocument())
        }
        assertThat(importer.staged.single().first.map { it.scheme }).containsExactly("content", "content")
        assertThat(importer.staged.single().second).isEqualTo(PageOrigin.SHARE)
    }

    @Test
    fun stagingFailureIsReported() = runTest {
        importer.failStage = true
        val vm = viewModel()
        vm.events.test {
            vm.importShared(listOf(Uri.parse("content://x/1")))
            assertThat(awaitItem()).isEqualTo(EntryEvent.Notice(EntryNotice.Failed))
        }
        assertThat(sessions.sessions).isEmpty()
    }
}
