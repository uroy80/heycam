package heycam.feature.scan.review

import android.app.Activity
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.domain.StageResult
import heycam.core.model.DocumentId
import heycam.core.model.ImportTarget
import heycam.core.model.ModuleState
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.Rotation
import heycam.core.model.UnsupportedReason
import heycam.core.scanner.ScanResult
import heycam.core.scanner.ScanStart
import heycam.feature.scan.FakePageRenderer
import heycam.feature.scan.FakeScanImporter
import heycam.feature.scan.FakeScanSessionStore
import heycam.feature.scan.FakeScannerLauncher
import heycam.feature.scan.FakeSettingsRepository
import heycam.feature.scan.MainDispatcherRule
import heycam.feature.scan.stagedImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReviewViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val sessions = FakeScanSessionStore()
    private val importer = FakeScanImporter()
    private val renderer = FakePageRenderer()
    private val launcher = FakeScannerLauncher()
    private val settings = FakeSettingsRepository()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()

    private suspend fun session(target: ImportTarget = ImportTarget.NewDocument(), pages: Int = 3): String =
        sessions.create(target, (1..pages).map { stagedImage("p$it") }).id

    private fun viewModel(id: String, extra: Map<String, Any?> = emptyMap()) =
        ReviewViewModel(sessions, importer, renderer, launcher, settings, SavedStateHandle(mapOf("sessionId" to id) + extra))

    @Test
    fun loadsTheSession() = runTest {
        val vm = viewModel(session())
        val state = vm.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.pages).hasSize(3)
        assertThat(state.current).isEqualTo(0)
        assertThat(state.canAdd).isTrue()
        assertThat(state.canDelete).isTrue()
    }

    @Test
    fun missingSessionCloses() = runTest {
        val vm = viewModel("gone")
        vm.events.test { assertThat(awaitItem()).isEqualTo(ReviewEvent.Closed) }
    }

    @Test
    fun filterRotateAndApplyToAllArePersisted() = runTest {
        val id = session()
        val vm = viewModel(id)
        vm.setFilter(PageFilter.AUTO)
        vm.rotate()
        assertThat(sessions.sessions.getValue(id).pages[0].edits.filter).isEqualTo(PageFilter.AUTO)
        assertThat(sessions.sessions.getValue(id).pages[0].edits.rotation).isEqualTo(Rotation.R90)
        assertThat(sessions.sessions.getValue(id).pages[1].edits.filter).isEqualTo(PageFilter.ORIGINAL)

        vm.applyToAll(PageFilter.BLACK_WHITE)
        assertThat(sessions.sessions.getValue(id).pages.map { it.edits.filter }).containsExactly(
            PageFilter.BLACK_WHITE, PageFilter.BLACK_WHITE, PageFilter.BLACK_WHITE,
        )
        // Rotation is per page and untouched by "apply to all".
        assertThat(sessions.sessions.getValue(id).pages[0].edits.rotation).isEqualTo(Rotation.R90)
    }

    @Test
    fun deleteDropsTheCurrentPageButNeverTheLast() = runTest {
        val id = session(pages = 2)
        val vm = viewModel(id)
        vm.select(1)
        vm.deleteCurrent()
        assertThat(vm.state.value.pages.map { it.image.file.name }).containsExactly("p1.jpg")
        assertThat(vm.state.value.current).isEqualTo(0)
        vm.deleteCurrent()
        assertThat(vm.state.value.pages).hasSize(1)
        assertThat(sessions.sessions.getValue(id).pages).hasSize(1)
    }

    @Test
    fun currentPageSurvivesProcessDeath() = runTest {
        val vm = viewModel(session(), mapOf("current" to 2))
        assertThat(vm.state.value.current).isEqualTo(2)
    }

    @Test
    fun saveCommitsDiscardsStagingAndOpensTheNewDocument() = runTest {
        val id = session()
        val vm = viewModel(id)
        vm.setFilter(PageFilter.GRAYSCALE)
        vm.events.test {
            vm.save()
            assertThat(awaitItem()).isEqualTo(ReviewEvent.Saved(DocumentId(42), newDocument = true))
        }
        assertThat(importer.committed.single().pages[0].edits.filter).isEqualTo(PageFilter.GRAYSCALE)
        assertThat(sessions.discarded).containsExactly(id)
        assertThat(vm.state.value.busy).isEqualTo(ReviewBusy.SAVING)
    }

    @Test
    fun saveFinishesEvenWhenTheScreenGoesAwayMidway() = runTest {
        val gate = CompletableDeferred<Unit>()
        importer.commitGate = gate
        val id = session()
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { viewModel(id) } })[ReviewViewModel::class.java]
        vm.save()
        assertThat(vm.state.value.busy).isEqualTo(ReviewBusy.SAVING)
        // Review is popped while the commit is still moving files: the view model is cleared and its scope cancelled.
        store.clear()
        gate.complete(Unit)
        assertThat(importer.committed.single().id).isEqualTo(id)
        assertThat(sessions.discarded).containsExactly(id)
    }

    @Test
    fun savingAddedPagesReturnsToTheirDocument() = runTest {
        val vm = viewModel(session(ImportTarget.AppendTo(DocumentId(5))))
        vm.events.test {
            vm.save()
            assertThat(awaitItem()).isEqualTo(ReviewEvent.Saved(DocumentId(42), newDocument = false))
        }
    }

    @Test
    fun failedSaveKeepsThePages() = runTest {
        importer.failCommit = true
        val id = session()
        val vm = viewModel(id)
        vm.events.test {
            vm.save()
            assertThat(awaitItem()).isEqualTo(ReviewEvent.Message(ReviewMessage.Failed))
        }
        assertThat(vm.state.value.busy).isEqualTo(ReviewBusy.IDLE)
        assertThat(sessions.discarded).isEmpty()
        // Still editable and saved to the session.
        vm.setFilter(PageFilter.AUTO)
        assertThat(sessions.sessions.getValue(id).pages[0].edits.filter).isEqualTo(PageFilter.AUTO)
    }

    @Test
    fun discardDeletesTheSessionAndCloses() = runTest {
        val id = session()
        val vm = viewModel(id)
        vm.events.test {
            vm.discard()
            assertThat(awaitItem()).isEqualTo(ReviewEvent.Closed)
        }
        assertThat(sessions.discarded).containsExactly(id)
        val updates = sessions.updates
        vm.setFilter(PageFilter.AUTO) // a late edit must not bring the session file back
        assertThat(sessions.updates).isEqualTo(updates)
        assertThat(sessions.sessions).doesNotContainKey(id)
    }

    @Test
    fun addPageFromScannerAppendsAndShowsTheFirstNewPage() = runTest {
        launcher.start = ScanStart.Immediate(ScanResult.Pages(listOf(stagedImage("n1"), stagedImage("n2"))))
        val id = session(pages = 2)
        val vm = viewModel(id)
        vm.addFromScanner(activity)
        assertThat(vm.state.value.pages.map { it.image.file.name }).containsExactly("p1.jpg", "p2.jpg", "n1.jpg", "n2.jpg").inOrder()
        assertThat(vm.state.value.current).isEqualTo(2)
        assertThat(vm.state.value.busy).isEqualTo(ReviewBusy.IDLE)
        assertThat(sessions.sessions.getValue(id).pages).hasSize(4)
    }

    @Test
    fun addPageWhileTheScannerDownloadsSaysSoAndFetchesIt() = runTest {
        launcher.availability = ModuleState.Downloading(0, null)
        val vm = viewModel(session())
        vm.events.test {
            vm.addFromScanner(activity)
            assertThat(awaitItem()).isEqualTo(ReviewEvent.Message(ReviewMessage.ScannerNotReady))
        }
        assertThat(launcher.installs).isEqualTo(1)
        assertThat(vm.state.value.busy).isEqualTo(ReviewBusy.IDLE)
    }

    @Test
    fun addPageOnAnUnsupportedPhone() = runTest {
        launcher.availability = ModuleState.Unsupported(UnsupportedReason.NO_PLAY_SERVICES)
        val vm = viewModel(session())
        vm.events.test {
            vm.addFromScanner(activity)
            assertThat(awaitItem()).isEqualTo(ReviewEvent.Message(ReviewMessage.ScannerUnavailable(UnsupportedReason.NO_PLAY_SERVICES)))
        }
    }

    @Test
    fun addPhotosAppendsAndReportsSkippedFiles() = runTest {
        importer.stageResult = StageResult(listOf(stagedImage("ph", PageOrigin.PHOTO_PICKER)), rejected = 1)
        val vm = viewModel(session(pages = 1))
        vm.events.test {
            vm.onPickerOpened()
            assertThat(vm.state.value.busy).isEqualTo(ReviewBusy.ADDING)
            vm.onPhotosPicked(listOf(Uri.parse("content://media/9"), Uri.parse("content://media/10")))
            assertThat(awaitItem()).isEqualTo(ReviewEvent.Message(ReviewMessage.Rejected(1)))
        }
        assertThat(vm.state.value.pages).hasSize(2)
        assertThat(vm.state.value.session!!.hasPhotos).isTrue()
        assertThat(vm.state.value.busy).isEqualTo(ReviewBusy.IDLE)
    }

    @Test
    fun retakeCannotAddPages() = runTest {
        val vm = viewModel(session(ImportTarget.Replace(PageId(3)), pages = 1))
        assertThat(vm.state.value.replacing).isTrue()
        assertThat(vm.state.value.canAdd).isFalse()
        vm.addFromScanner(activity)
        assertThat(launcher.requests).isEmpty()
    }

    @Test
    fun previewsAreCached() = runTest {
        val vm = viewModel(session())
        val page = vm.state.value.pages[0]
        assertThat(vm.preview(page, page.edits, 200)).isNotNull()
        assertThat(vm.preview(page, page.edits, 200)).isNotNull()
        vm.preview(page, page.edits.copy(filter = PageFilter.AUTO), 200)
        assertThat(renderer.previews).hasSize(2)
    }
}
