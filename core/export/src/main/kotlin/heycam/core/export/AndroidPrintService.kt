package heycam.core.export

import android.app.Activity
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import heycam.core.common.ApplicationScope
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Printing through the system print dialog (which also offers "Save as PDF"): the requested pages become one PDF
 * without a text layer, handed to PrintManager. Throws [IOException] when the PDF cannot be made.
 */
class AndroidPrintService @Inject constructor(
    private val exports: DefaultExportService,
    private val dispatchers: DispatcherProvider,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val log: HeycamLog,
) : PrintService {
    override suspend fun print(activity: Activity, request: ExportRequest) {
        val printable = exports.printablePdf(request)
        withContext(dispatchers.main) {
            val manager = activity.getSystemService(PrintManager::class.java)
            if (manager == null) {
                printable.file.delete()
                throw IOException("Printing is not available")
            }
            manager.print(printable.jobName, PdfPrintAdapter(printable, scope, dispatchers, log), null)
        }
    }
}

/** Hands a finished PDF to the print framework, copying it off the main thread; deletes it when printing ends. */
internal class PdfPrintAdapter(
    private val printable: PrintablePdf,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val log: HeycamLog,
) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(printable.file.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(printable.pageCount)
            .build()
        // The PDF has its own page sizes; a new paper size or orientation does not change it.
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        scope.launch(dispatchers.io) {
            val result = try {
                printable.file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                null
            } catch (e: IOException) {
                log.w(TAG, "Print copy failed", e)
                e
            }
            withContext(dispatchers.main) {
                when {
                    cancellationSignal?.isCanceled == true -> callback.onWriteCancelled()
                    result != null -> callback.onWriteFailed(null)
                    else -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }
            }
        }
    }

    override fun onFinish() {
        printable.file.parentFile?.deleteRecursively()
    }

    private companion object {
        const val TAG = "Print"
    }
}
