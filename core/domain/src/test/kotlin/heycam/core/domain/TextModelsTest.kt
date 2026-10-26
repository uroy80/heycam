package heycam.core.domain

import com.google.common.truth.Truth.assertThat
import heycam.core.model.ImageInfo
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.UnsupportedReason
import heycam.core.ocr.OcrEngine
import heycam.core.ocr.OcrOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TextModelsTest {
    /** Emits [states], then would keep going (as a status stream might) if nobody stopped collecting. */
    private class StreamingEngine(private val states: List<ModuleState>) : OcrEngine {
        var asked: Set<OcrScript>? = null
        var emittedAfterEnd = false

        override fun ensureModels(scripts: Set<OcrScript>): Flow<ModuleState> = flow {
            asked = scripts
            states.forEach { emit(it) }
            emittedAfterEnd = true
            emit(ModuleState.Checking)
        }

        override suspend fun recognize(image: ImageInfo, script: OcrScript): OcrOutcome =
            throw UnsupportedOperationException("not used")
    }

    @Test
    fun endsAtReady() = runTest {
        val engine = StreamingEngine(listOf(ModuleState.Checking, ModuleState.Downloading(1, 2), ModuleState.Installing, ModuleState.Ready))
        val states = OcrTextModels(engine).download(setOf(OcrScript.CHINESE, OcrScript.KOREAN)).toList()
        assertThat(states.last()).isEqualTo(ModuleState.Ready)
        assertThat(states).hasSize(4)
        assertThat(engine.asked).containsExactly(OcrScript.CHINESE, OcrScript.KOREAN)
        assertThat(engine.emittedAfterEnd).isFalse()
    }

    @Test
    fun endsAtFailureAndAtUnsupported() = runTest {
        val failed = ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true)
        assertThat(OcrTextModels(StreamingEngine(listOf(ModuleState.Checking, failed))).download(setOf(OcrScript.LATIN)).toList())
            .containsExactly(ModuleState.Checking, failed).inOrder()
        val unsupported = ModuleState.Unsupported(UnsupportedReason.LOW_RAM)
        assertThat(OcrTextModels(StreamingEngine(listOf(unsupported))).download(setOf(OcrScript.LATIN)).toList())
            .containsExactly(unsupported)
    }
}
