package heycam.core.scanner

import android.app.Activity
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import heycam.core.model.ModuleState
import heycam.core.model.StagedImage
import kotlinx.coroutines.flow.Flow

/**
 * Seam over the ML Kit document scanner. Production: GmsScannerLauncher (SCANNER_MODE_FULL, gallery import on,
 * RESULT_FORMAT_JPEG only). Tests and screenshot runs: FakeScannerLauncher (bundled sample pages, :core:testing).
 */
interface ScannerLauncher {
    /** Play services present, RAM >= 1.7 GB, scanner module installed? */
    suspend fun availability(): ModuleState
    /** Installs the scanner module via ModuleInstallClient with progress, so users never see Google's error screen. */
    fun install(): Flow<ModuleState>
    suspend fun start(activity: Activity, request: ScanRequest): ScanStart
    /** Copies result JPEGs into heycam staging, then deletes ML Kit's copies in cacheDir/mlkit_docscan_ui_client. */
    suspend fun finish(resultCode: Int, data: Intent?): ScanResult
}

data class ScanRequest(
    val pageLimit: Int? = null,       // null = heycam's most (100); 1 for retake; 2 for v1.1 ID card
    val allowGallery: Boolean = true,
)

sealed interface ScanStart {
    data class Launch(val request: IntentSenderRequest) : ScanStart
    data class Immediate(val result: ScanResult) : ScanStart      // fakes
    data class Unavailable(val state: ModuleState) : ScanStart
}

sealed interface ScanResult {
    data class Pages(val images: List<StagedImage>) : ScanResult
    data object Cancelled : ScanResult
    data class Failed(val errorClass: String) : ScanResult
}
