package heycam.core.domain

import android.net.Uri
import heycam.core.common.Clock
import heycam.core.common.HeycamLog
import heycam.core.data.DocumentRepository
import heycam.core.data.PageWorkStore
import heycam.core.data.ScanSessionStore
import heycam.core.imaging.ImageImporter
import heycam.core.imaging.ImportedImage
import heycam.core.model.DocumentId
import heycam.core.model.ImportTarget
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.model.StagedImage
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Intake (F1–F3): photos and shared images are copied into staging one at a time (P7: at most 100, each checked by
 * decoding), and a reviewed session becomes a document, extra pages or a retaken page.
 */
class DefaultScanImporter @Inject constructor(
    private val importer: ImageImporter,
    private val documents: DocumentRepository,
    private val work: PageWorkStore,
    private val sessions: ScanSessionStore,
    private val processor: QueuedPageProcessor,
    private val titles: DefaultTitle,
    private val clock: Clock,
    private val log: HeycamLog,
) : ScanImporter {

    override suspend fun stage(uris: List<Uri>, origin: PageOrigin): StageResult {
        val accepted = uris.take(MAX_IMAGES)
        var rejected = uris.size - accepted.size
        val images = ArrayList<StagedImage>(accepted.size)
        for (uri in accepted) {
            val result = try {
                importer.import(uri, origin)
            } catch (e: CancellationException) {
                images.forEach { it.file.delete() }
                throw e
            } catch (e: Exception) {
                log.w(TAG, "Import failed", e)
                null
            }
            if (result is ImportedImage.Ok) images += result.image else rejected++
        }
        return StageResult(images, rejected)
    }

    /**
     * Saves the session's pages, deletes the session (its files now belong to the document) and wakes the processor.
     * Throws when the session is empty or its target is gone; the session is then kept so nothing is lost.
     * Once started it runs to the end even if the caller is cancelled: a save that went through must also discard
     * the session and wake the processor, or the pages would wait unread behind an empty session.
     */
    override suspend fun commit(session: ScanSession): CommitResult = withContext(NonCancellable) {
        require(session.pages.isNotEmpty()) { "nothing to save" }
        val result = when (val target = session.target) {
            is ImportTarget.NewDocument -> {
                val title = target.title?.trim()?.takeIf { it.isNotEmpty() }
                    ?: titles.forNewDocument(clock.now(), ZoneId.systemDefault(), Locale.getDefault())
                val id = documents.createDocument(title, target.folderId, session.pages)
                CommitResult(id, documents.observeDocument(id).first()?.pages?.map { it.id }.orEmpty())
            }
            is ImportTarget.AppendTo ->
                CommitResult(target.documentId, documents.addPages(target.documentId, session.pages, target.atIndex))
            is ImportTarget.Replace -> CommitResult(replace(target.pageId, session), listOf(target.pageId))
        }
        sessions.discard(session.id)
        processor.kick()
        result
    }

    /**
     * Retake. The contract has no page-to-document lookup, so the replaced page is found among the pages waiting
     * for a render, which a retake always is. The processor's page lock keeps it from rendering the page in between.
     */
    private suspend fun replace(pageId: PageId, session: ScanSession): DocumentId = processor.pageLock.withLock {
        documents.replacePage(pageId, session.pages.first())
        work.pagesNeedingRender(Int.MAX_VALUE).firstOrNull { it.id == pageId }?.documentId
            ?: throw NoSuchElementException("retaken page ${pageId.value} not found")
    }

    private companion object {
        const val TAG = "ScanImporter"
        const val MAX_IMAGES = 100
    }
}
