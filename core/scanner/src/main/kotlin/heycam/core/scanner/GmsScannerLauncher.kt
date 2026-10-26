package heycam.core.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dagger.hilt.android.qualifiers.ApplicationContext
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.common.StagingArea
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.UnsupportedReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The ML Kit document scanner (Google's capture, crop and cleanup screens) in full mode with gallery import, JPEG
 * results only: ML Kit's own PDF has no text layer, so heycam builds its own (PLAN 4.6).
 *
 * A scanner module that is not on the phone yet reports `Downloading(0, null)` from [availability]: it has to be
 * fetched, and [install] does that with progress, so people never see Google's download error screen (mlkit-spike).
 */
@Singleton
class GmsScannerLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    staging: StagingArea,
    private val dispatchers: DispatcherProvider,
    private val log: HeycamLog,
) : ScannerLauncher {
    private val installer: ModuleInstallClient by lazy { ModuleInstall.getClient(context) }
    private val stager = ScanPageStager(context, staging)

    /** Any client stands for the scanner module in availability and install requests. */
    private val module: GmsDocumentScanner by lazy { GmsDocumentScanning.getClient(options(ScanRequest())) }

    override suspend fun availability(): ModuleState {
        ScannerInstall.unsupported(context)?.let { return ModuleState.Unsupported(it) }
        return try {
            if (installer.areModulesAvailable(module).await().areModulesAvailable()) ModuleState.Ready else NOT_INSTALLED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "Scanner check failed", e)
            ScannerInstall.failureOf(e)
        }
    }

    override fun install(): Flow<ModuleState> = flow {
        emit(ModuleState.Checking)
        when (val now = availability()) {
            ModuleState.Ready, is ModuleState.Unsupported, is ModuleState.Failed -> {
                emit(now)
                return@flow
            }
            else -> Unit
        }
        if (!ScannerInstall.online(context)) {
            emit(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
            return@flow
        }
        emitAll(ScannerInstall.install(installer, listOf(module)))
    }

    override suspend fun start(activity: Activity, request: ScanRequest): ScanStart {
        ScannerInstall.unsupported(context)?.let { return ScanStart.Unavailable(ModuleState.Unsupported(it)) }
        return try {
            val sender = GmsDocumentScanning.getClient(options(request)).getStartScanIntent(activity).await()
            ScanStart.Launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: MlKitException) {
            log.w(TAG, "Scanner did not start (code ${e.errorCode})", e)
            when (e.errorCode) {
                MlKitException.UNSUPPORTED -> ScanStart.Unavailable(ModuleState.Unsupported(UnsupportedReason.LOW_RAM))
                MlKitException.UNAVAILABLE -> ScanStart.Unavailable(NOT_INSTALLED)
                else -> ScanStart.Immediate(ScanResult.Failed("MlKitException:${e.errorCode}"))
            }
        } catch (e: Exception) {
            log.w(TAG, "Scanner did not start", e)
            ScanStart.Immediate(ScanResult.Failed(e.javaClass.simpleName))
        }
    }

    override suspend fun finish(resultCode: Int, data: Intent?): ScanResult {
        if (resultCode == Activity.RESULT_CANCELED) return ScanResult.Cancelled
        if (resultCode != Activity.RESULT_OK) return ScanResult.Failed("ResultCode:$resultCode")
        val uris = GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages.orEmpty().map { it.imageUri }
        if (uris.isEmpty()) return ScanResult.Cancelled
        return try {
            ScanResult.Pages(withContext(dispatchers.io) { stager.stage(uris) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "Scanner pages could not be kept", e)
            ScanResult.Failed(e.javaClass.simpleName)
        }
    }

    private fun options(request: ScanRequest): GmsDocumentScannerOptions = GmsDocumentScannerOptions.Builder()
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .setGalleryImportAllowed(request.allowGallery)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setPageLimit(pageLimitFor(request))
        .build()

    internal companion object {
        private const val TAG = "Scanner"
        private val NOT_INSTALLED = ModuleState.Downloading(bytesDownloaded = 0, totalBytes = null)

        /** Without a limit ML Kit takes as many pages as the phone allows, more than heycam keeps; it stops at ours. */
        fun pageLimitFor(request: ScanRequest): Int = (request.pageLimit ?: MAX_SCAN_PAGES).coerceIn(1, MAX_SCAN_PAGES)
    }
}
