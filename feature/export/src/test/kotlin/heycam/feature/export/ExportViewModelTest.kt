package heycam.feature.export

import android.app.Activity
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import org.robolectric.RobolectricTestRunner
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.export.ExportFailure
import heycam.core.export.ExportResult
import heycam.core.export.ExportTarget
import heycam.core.export.TextPendingPolicy
import heycam.core.model.ExportFormat
import heycam.core.model.ExportOptions
import heycam.core.model.Margin
import heycam.core.model.PageSize
import heycam.core.model.Settings
import heycam.core.model.SizePreset
import heycam.core.model.TextStatus
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExportViewModelTest {
    private val documents = FakeDocumentRepository(
        testDocument(1, title = "Lease agreement", pages = 3),
        testDocument(2, title = "Receipt", pages = 1),
        testDocument(3, title = "Pending", pages = 4, textStatus = TextStatus.PENDING),
        testDocument(4, title = "Old", trashedAt = Instant.EPOCH),
    )
    private val settings = FakeSettingsRepository(
        Settings(exportDefaults = ExportOptions(size = SizePreset.ORIGINAL, pageSize = PageSize.A4, margin = Margin.NARROW)),
    )
    private val exportService = FakeExportService()
    private val printService = FakePrintService()
    private val processor = CountingPageProcessor()
    private val log = RecordingLog()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun create(vararg ids: Long, compress: Boolean = false, saved: SavedStateHandle = SavedStateHandle()) =
        ExportViewModel(ExportArgs(ids.toList(), compress), saved, documents, settings, exportService, printService, processor, log)

    private fun TestScope.viewModel(vararg ids: Long, compress: Boolean = false, saved: SavedStateHandle = SavedStateHandle()): ExportViewModel {
        val vm = create(*ids, compress = compress, saved = saved)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun startsFromTheExportDefaults() = runTest {
        val state = viewModel(1).uiState.value
        assertThat(state.loading).isFalse()
        assertThat(state.size).isEqualTo(SizePreset.ORIGINAL)
        assertThat(state.pageSize).isEqualTo(PageSize.A4)
        assertThat(state.margin).isEqualTo(Margin.NARROW)
        assertThat(state.fileName).isEqualTo("Lease agreement")
        assertThat(state.pageCount).isEqualTo(3)
    }

    @Test
    fun compressPresetsSmall() = runTest {
        assertThat(viewModel(1, compress = true).uiState.value.size).isEqualTo(SizePreset.SMALL)
    }

    @Test
    fun measuresEveryPresetOneAtATime() = runTest {
        val state = viewModel(1).uiState.value
        assertThat(state.estimates.keys).containsExactly(SizePreset.SMALL, SizePreset.STANDARD, SizePreset.ORIGINAL)
        assertThat(state.estimates[SizePreset.SMALL]).isEqualTo(150_000L)
        assertThat(exportService.estimates.map { it.options.size })
            .containsExactly(SizePreset.SMALL, SizePreset.STANDARD, SizePreset.ORIGINAL).inOrder()
        assertThat(exportService.estimates.map { it.whenTextPending }.toSet()).containsExactly(TextPendingPolicy.WITHOUT_TEXT)
    }

    @Test
    fun textFormatIsNotMeasured() = runTest {
        val vm = viewModel(1)
        exportService.estimates.clear()
        vm.setFormat(ExportFormat.TEXT)
        advanceUntilIdle()
        assertThat(exportService.estimates).isEmpty()
        assertThat(vm.uiState.value.estimates).isEmpty()
    }

    @Test
    fun anEstimateThatFailsIsMarkedUnknown() = runTest {
        exportService.estimateFailure = IOException("/data/user/0/in.edgr.heycam/files/pages/x/source.jpg")
        val state = viewModel(1).uiState.value
        assertThat(state.estimates).containsExactly(SizePreset.SMALL, null, SizePreset.STANDARD, null, SizePreset.ORIGINAL, null)
        assertThat(log.lines).contains("W/Export estimate failed: IOException")
    }

    @Test
    fun trashedAndMissingDocumentsAreNotExported() = runTest {
        assertThat(viewModel(4).uiState.value.unavailable).isTrue()
        assertThat(viewModel(99).uiState.value.unavailable).isTrue()
        val mixed = viewModel(1, 4).uiState.value
        assertThat(mixed.documents.map { it.id.value }).containsExactly(1L)
    }

    @Test
    fun shareHandsBackTheChooser() = runTest {
        val vm = viewModel(1)
        vm.events.test {
            vm.share()
            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(ExportEvent.Share::class.java)
        }
        val (request, target) = exportService.exports.single()
        assertThat(target).isEqualTo(ExportTarget.Share)
        assertThat(request.options.size).isEqualTo(SizePreset.ORIGINAL)
        assertThat(request.options.fileName).isNull()
        assertThat(vm.uiState.value.running).isFalse()
    }

    @Test
    fun saveOneFileAsksForADocumentThenWritesIt() = runTest {
        val vm = viewModel(1)
        vm.setFileName("  Flat 4B  ")
        val uri = Uri.parse("content://docs/flat")
        vm.events.test {
            vm.save()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ExportEvent.PickFile("application/pdf", "Flat 4B.pdf"))
            vm.onFilePicked(uri)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ExportEvent.Saved(1))
        }
        val (request, target) = exportService.exports.single()
        assertThat(target).isEqualTo(ExportTarget.Document(uri))
        assertThat(request.options.fileName).isEqualTo("Flat 4B")
    }

    @Test
    fun cancelledPickerExportsNothing() = runTest {
        val vm = viewModel(1)
        vm.save()
        vm.onFilePicked(null)
        advanceUntilIdle()
        assertThat(exportService.exports).isEmpty()
    }

    @Test
    fun severalFilesGoToAFolder() = runTest {
        val vm = viewModel(1)
        vm.setFormat(ExportFormat.JPEG)
        val tree = Uri.parse("content://tree/scans")
        vm.events.test {
            vm.save()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ExportEvent.PickFolder)
            vm.onFolderPicked(tree)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ExportEvent.Saved(2))
        }
        assertThat(exportService.exports.single().second).isEqualTo(ExportTarget.Folder(tree))
    }

    @Test
    fun severalDocumentsGoToAFolderWithTheirOwnNames() = runTest {
        val vm = viewModel(1, 2)
        vm.setFileName("ignored")
        vm.events.test {
            vm.save()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ExportEvent.PickFolder)
        }
        vm.onFolderPicked(Uri.parse("content://tree/x"))
        advanceUntilIdle()
        assertThat(exportService.exports.single().first.options.fileName).isNull()
    }

    @Test
    fun pendingTextAsksFirstAndWaitIsPassedOn() = runTest {
        val vm = viewModel(3)
        vm.share()
        advanceUntilIdle()
        assertThat(vm.uiState.value.textPendingPages).isEqualTo(4)
        assertThat(vm.uiState.value.canExport).isFalse()
        assertThat(exportService.exports).isEmpty()

        assertThat(processor.kicks).isEqualTo(0)
        vm.answerTextPending(wait = true)
        advanceUntilIdle()
        assertThat(vm.uiState.value.textPendingPages).isNull()
        assertThat(exportService.exports.single().first.whenTextPending).isEqualTo(TextPendingPolicy.WAIT)
        assertThat(processor.kicks).isEqualTo(1) // something must be reading while the export waits
    }

    @Test
    fun pendingTextCanBeSkipped() = runTest {
        val vm = viewModel(3)
        vm.events.test {
            vm.save()
            advanceUntilIdle()
            vm.answerTextPending(wait = false)
            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(ExportEvent.PickFile::class.java)
        }
        vm.onFilePicked(Uri.parse("content://docs/p"))
        advanceUntilIdle()
        assertThat(exportService.exports.single().first.whenTextPending).isEqualTo(TextPendingPolicy.WITHOUT_TEXT)
    }

    @Test
    fun imagesNeverWaitForText() = runTest {
        val vm = viewModel(3)
        vm.setFormat(ExportFormat.PNG)
        vm.share()
        advanceUntilIdle()
        assertThat(vm.uiState.value.textPendingPages).isNull()
        assertThat(exportService.exports.single().first.whenTextPending).isEqualTo(TextPendingPolicy.WITHOUT_TEXT)
    }

    @Test
    fun plainPdfDoesNotWaitForText() = runTest {
        val vm = viewModel(3)
        vm.setSearchable(false)
        vm.share()
        advanceUntilIdle()
        assertThat(exportService.exports.single().first.whenTextPending).isEqualTo(TextPendingPolicy.WITHOUT_TEXT)
    }

    @Test
    fun serviceSaysTextPendingAfterPickingThenSameTargetIsReused() = runTest {
        val vm = viewModel(1)
        var first = true
        exportService.result = { _, target ->
            if (first) {
                first = false
                ExportResult.TextPending(2)
            } else {
                ExportResult.Saved(listOf((target as ExportTarget.Document).uri))
            }
        }
        val uri = Uri.parse("content://docs/a")
        vm.save()
        vm.onFilePicked(uri)
        advanceUntilIdle()
        assertThat(vm.uiState.value.textPendingPages).isEqualTo(2)

        vm.answerTextPending(wait = true)
        advanceUntilIdle()
        assertThat(exportService.exports.map { it.second }).containsExactly(ExportTarget.Document(uri), ExportTarget.Document(uri))
        assertThat(exportService.exports.last().first.whenTextPending).isEqualTo(TextPendingPolicy.WAIT)
    }

    @Test
    fun dismissingTheQuestionAfterPickingRemovesThePickedFile() = runTest {
        val vm = viewModel(1)
        exportService.result = { _, _ -> ExportResult.TextPending(2) }
        val uri = Uri.parse("content://docs/a")
        vm.save()
        vm.onFilePicked(uri)
        advanceUntilIdle()
        assertThat(vm.uiState.value.textPendingPages).isEqualTo(2)

        vm.dismissTextPending()
        advanceUntilIdle()
        assertThat(vm.uiState.value.textPendingPages).isNull()
        assertThat(exportService.discarded).containsExactly(ExportTarget.Document(uri))
    }

    @Test
    fun dismissingTheQuestionBeforePickingLeavesNothingToRemove() = runTest {
        val vm = viewModel(3)
        vm.share()
        advanceUntilIdle()
        vm.dismissTextPending()
        advanceUntilIdle()
        assertThat(vm.uiState.value.textPendingPages).isNull()
        assertThat(exportService.discarded).isEmpty()
        assertThat(exportService.exports).isEmpty()
    }

    @Test
    fun failuresBecomeShortErrors() = runTest {
        val vm = viewModel(1)
        exportService.result = { _, _ -> ExportResult.Failed(ExportFailure.NO_SPACE) }
        vm.share()
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).isEqualTo(ExportError.NO_SPACE)

        vm.setFormat(ExportFormat.JPEG)
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).isNull()

        exportService.result = { _, _ -> ExportResult.Failed(ExportFailure.CANCELLED) }
        vm.share()
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun crashesAreLoggedWithoutContent() = runTest {
        val vm = viewModel(1)
        exportService.failure = IOException("/storage/emulated/0/Lease agreement.pdf")
        vm.share()
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).isEqualTo(ExportError.WRITE_FAILED)
        assertThat(log.lines).containsExactly("E/Export export failed: IOException")
    }

    @Test
    fun printUsesPdfAndNeverAsksAboutText() = runTest {
        val vm = viewModel(3)
        vm.setFormat(ExportFormat.TEXT)
        vm.events.test {
            vm.print()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ExportEvent.Print)
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        vm.printNow(activity)
        val request = printService.requests.single()
        assertThat(request.options.format).isEqualTo(ExportFormat.PDF)
        assertThat(request.whenTextPending).isEqualTo(TextPendingPolicy.WITHOUT_TEXT)
        assertThat(vm.uiState.value.running).isFalse()
    }

    @Test
    fun printFailureIsShown() = runTest {
        val vm = viewModel(1)
        printService.failure = IllegalStateException("boom")
        vm.print()
        vm.printNow(Robolectric.buildActivity(Activity::class.java).setup().get())
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).isEqualTo(ExportError.PRINT_FAILED)
    }

    @Test
    fun choicesAreFrozenWhileRunning() = runTest {
        val vm = viewModel(1)
        vm.share()
        vm.setFormat(ExportFormat.PNG)
        advanceUntilIdle()
        assertThat(vm.uiState.value.format).isEqualTo(ExportFormat.PDF)
        assertThat(exportService.exports.single().first.options.format).isEqualTo(ExportFormat.PDF)
    }

    @Test
    fun choicesSurviveTheProcess() = runTest {
        val saved = SavedStateHandle()
        val first = viewModel(1, saved = saved)
        first.setFormat(ExportFormat.PNG)
        first.setSize(SizePreset.SMALL)
        first.setFileName("Flat 4B")
        advanceUntilIdle()

        settings.loaded.value = false // the restored sheet must not need the defaults
        val restored = viewModel(1, saved = saved).uiState.value
        assertThat(restored.loading).isFalse()
        assertThat(restored.format).isEqualTo(ExportFormat.PNG)
        assertThat(restored.size).isEqualTo(SizePreset.SMALL)
        assertThat(restored.pageSize).isEqualTo(PageSize.A4)
        assertThat(restored.fileName).isEqualTo("Flat 4B")
    }

    @Test
    fun aPickedFileArrivingBeforeAnythingLoadedIsWrittenWithTheSavedChoices() = runTest {
        val saved = SavedStateHandle()
        val before = viewModel(1, saved = saved)
        before.setFormat(ExportFormat.JPEG)
        before.setFileName("x")
        advanceUntilIdle()

        // The process ends while the system picker is open; the restored sheet gets the picked file first thing.
        settings.loaded.value = false
        documents.loaded.value = false
        val uri = Uri.parse("content://docs/x")
        val vm = create(1, saved = saved)
        vm.onFilePicked(uri)
        advanceUntilIdle()
        assertThat(exportService.exports).isEmpty()

        documents.loaded.value = true
        advanceUntilIdle()
        val (request, target) = exportService.exports.single()
        assertThat(target).isEqualTo(ExportTarget.Document(uri))
        assertThat(request.options.format).isEqualTo(ExportFormat.JPEG)
        assertThat(request.options.fileName).isEqualTo("x")
    }

    @Test
    fun aPickedFileWaitsForTheDefaultsWhenNothingWasSaved() = runTest {
        settings.loaded.value = false
        val vm = create(1)
        vm.onFilePicked(Uri.parse("content://docs/y"))
        advanceUntilIdle()
        assertThat(exportService.exports).isEmpty()

        settings.loaded.value = true
        advanceUntilIdle()
        assertThat(exportService.exports.single().first.options.size).isEqualTo(SizePreset.ORIGINAL)
    }

    @Test
    fun theTextAnswerSurvivesWhileThePickerIsOpen() = runTest {
        val saved = SavedStateHandle()
        val before = viewModel(3, saved = saved)
        before.events.test {
            before.save()
            advanceUntilIdle()
            before.answerTextPending(wait = false)
            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(ExportEvent.PickFile::class.java)
        }

        val vm = create(3, saved = saved)
        vm.onFilePicked(Uri.parse("content://docs/p"))
        advanceUntilIdle()
        assertThat(exportService.exports.single().first.whenTextPending).isEqualTo(TextPendingPolicy.WITHOUT_TEXT)
    }

    @Test
    fun missingAppIsReported() = runTest {
        val vm = viewModel(1)
        vm.onNoApp()
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).isEqualTo(ExportError.NO_APP)
    }
}
