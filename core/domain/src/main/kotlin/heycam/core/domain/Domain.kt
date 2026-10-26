package heycam.core.domain

import android.net.Uri
import heycam.core.model.DocumentId
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.model.StagedImage
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

interface ScanImporter {
    /** Photo Picker / share intake: ImageImporter per uri (max 100 uris, 50 MB each). */
    suspend fun stage(uris: List<Uri>, origin: PageOrigin): StageResult
    /** Session -> repository (create / append / replace) in one transaction, then PageProcessor.kick(). */
    suspend fun commit(session: ScanSession): CommitResult
}

data class StageResult(val images: List<StagedImage>, val rejected: Int)

data class CommitResult(val documentId: DocumentId, val pageIds: List<PageId>)

/**
 * Single background pipeline on @ApplicationScope, one page at a time: render (edits) -> thumbnail -> read text.
 * Kicked at app start, after commits and after edits. No WorkManager, no foreground service: unfinished pages stay
 * PENDING in the database and are picked up at the next kick.
 */
interface PageProcessor {
    val state: StateFlow<ProcessorState>
    fun kick()
    suspend fun awaitIdle(documentIds: Collection<DocumentId>? = null)
    /** "Read again as..." from the text view: marks pages PENDING with the new script override. */
    suspend fun reread(documentId: DocumentId, script: OcrScript?)
    /**
     * The user's Retry after reading or a text model failed: models that kept failing get fresh download attempts,
     * then [kick]. Pages the caller set back to PENDING are rendered and read again.
     */
    fun retry() = kick()
}

data class ProcessorState(
    val pagesToRender: Int,
    val pagesToRead: Int,
    val current: DocumentId?,
    val models: ModuleState?,          // non-null while text models are missing / downloading
)

/** "Scan 20 Oct 2026, 14:32" in the user's locale; file-name sanitising happens at export. */
fun interface DefaultTitle {
    fun forNewDocument(at: Instant, zone: ZoneId, locale: Locale): String
}
