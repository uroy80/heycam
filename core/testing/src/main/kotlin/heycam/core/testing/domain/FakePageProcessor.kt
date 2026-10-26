package heycam.core.testing.domain

import heycam.core.domain.PageProcessor
import heycam.core.domain.ProcessorState
import heycam.core.model.DocumentId
import heycam.core.model.OcrScript
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * [PageProcessor] that does no work: tests set [state] by hand. [awaitIdle] returns once the state shows nothing
 * left to render or read; [kick], [retry] and [reread] are counted and recorded (a retry or reread also kicks).
 */
class FakePageProcessor : PageProcessor {
    private val _state = MutableStateFlow(ProcessorState(pagesToRender = 0, pagesToRead = 0, current = null, models = null))
    override val state: StateFlow<ProcessorState> = _state.asStateFlow()

    private val _kicks = AtomicInteger()
    val kicks: Int get() = _kicks.get()

    private val _retries = AtomicInteger()
    val retries: Int get() = _retries.get()

    private val _rereads = Collections.synchronizedList(mutableListOf<Pair<DocumentId, OcrScript?>>())
    val rereads: List<Pair<DocumentId, OcrScript?>> get() = synchronized(_rereads) { _rereads.toList() }

    fun setState(state: ProcessorState) {
        _state.value = state
    }

    override fun kick() {
        _kicks.incrementAndGet()
    }

    override fun retry() {
        _retries.incrementAndGet()
        kick()
    }

    override suspend fun awaitIdle(documentIds: Collection<DocumentId>?) {
        _state.first { it.pagesToRender == 0 && it.pagesToRead == 0 }
    }

    override suspend fun reread(documentId: DocumentId, script: OcrScript?) {
        _rereads += documentId to script
        kick()
    }
}
