package heycam.feature.document

import heycam.core.common.ApplicationScope
import heycam.core.data.DocumentRepository
import heycam.core.model.PageId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page delete with Undo (F6): the page disappears at once and is deleted for good only after the undo window, on
 * the app scope, so leaving the screen does not lose or cancel it. Shared by the grid and the page viewer.
 */
@Singleton
internal class PageDeletions @Inject constructor(
    private val documents: DocumentRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _pending = MutableStateFlow<Set<PageId>>(emptySet())
    /** Pages hidden while their Undo is on offer. */
    val pending: StateFlow<Set<PageId>> = _pending.asStateFlow()

    private val _failed = MutableSharedFlow<PageId>(extraBufferCapacity = 8)
    /** Deletes that did not go through; the page is back. */
    val failed: Flow<PageId> = _failed.asSharedFlow()

    private val jobs = ConcurrentHashMap<PageId, Job>()

    /**
     * Hides the page now and deletes it after [windowMs], the time its Undo is on offer: [UNDO_WINDOW_MS], or longer
     * when the user's accessibility settings ask for more time to act.
     */
    fun delete(id: PageId, windowMs: Long = UNDO_WINDOW_MS) {
        if (id in _pending.value) return
        _pending.update { it + id }
        jobs[id] = scope.launch {
            try {
                delay(windowMs)
                jobs.remove(id) // past this point Undo is no longer offered
                documents.deletePages(listOf(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _failed.tryEmit(id)
            } finally {
                jobs.remove(id)
                _pending.update { it - id }
            }
        }
    }

    /** True if the page was still waiting and is now kept. */
    fun undo(id: PageId): Boolean {
        val job = jobs.remove(id) ?: return false
        job.cancel()
        _pending.update { it - id }
        return true
    }

    companion object {
        /** How long Undo is offered (PLAN F6) unless the accessibility timeout asks for longer. */
        const val UNDO_WINDOW_MS = 6_000L
    }
}
