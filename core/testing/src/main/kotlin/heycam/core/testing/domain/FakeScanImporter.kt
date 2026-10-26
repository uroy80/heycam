package heycam.core.testing.domain

import android.net.Uri
import heycam.core.common.StagingArea
import heycam.core.domain.CommitResult
import heycam.core.domain.ScanImporter
import heycam.core.domain.StageResult
import heycam.core.model.DocumentId
import heycam.core.model.ImportTarget
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.testing.ocr.OcrSample
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * [ScanImporter] without a database. [stage] copies [sample] into [staging] once per uri, except uris listed in
 * [rejected]; [commit] records the session and hands out new ids (a retake reports [retakeDocument]).
 */
class FakeScanImporter(
    private val staging: StagingArea,
    var sample: OcrSample = OcrSample.EN_HI,
) : ScanImporter {
    val rejected: MutableSet<Uri> = Collections.synchronizedSet(mutableSetOf())
    var retakeDocument: DocumentId = DocumentId(1)

    private val _committed = Collections.synchronizedList(mutableListOf<ScanSession>())
    val committed: List<ScanSession> get() = synchronized(_committed) { _committed.toList() }

    private val ids = AtomicLong(100)

    override suspend fun stage(uris: List<Uri>, origin: PageOrigin): StageResult {
        val accepted = uris.filterNot { it in rejected }
        return StageResult(accepted.map { sample.stage(staging, origin) }, rejected = uris.size - accepted.size)
    }

    override suspend fun commit(session: ScanSession): CommitResult {
        require(session.pages.isNotEmpty()) { "nothing to save" }
        _committed += session
        return when (val target = session.target) {
            is ImportTarget.NewDocument -> CommitResult(DocumentId(ids.incrementAndGet()), newPageIds(session.pages.size))
            is ImportTarget.AppendTo -> CommitResult(target.documentId, newPageIds(session.pages.size))
            is ImportTarget.Replace -> CommitResult(retakeDocument, listOf(target.pageId))
        }
    }

    private fun newPageIds(count: Int) = List(count) { PageId(ids.incrementAndGet()) }
}
