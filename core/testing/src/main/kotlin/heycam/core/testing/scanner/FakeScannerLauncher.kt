package heycam.core.testing.scanner

import android.app.Activity
import android.content.Intent
import heycam.core.common.StagingArea
import heycam.core.model.ModuleState
import heycam.core.model.PageOrigin
import heycam.core.scanner.ScanRequest
import heycam.core.scanner.ScanResult
import heycam.core.scanner.ScanStart
import heycam.core.scanner.ScannerLauncher
import heycam.core.testing.ocr.OcrSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [ScannerLauncher] without Google's scanner screens (PLAN 4.13): [start] answers at once with
 * [ScanStart.Immediate], staging fresh copies of [pages] (at most the request's page limit) as GmsScannerLauncher
 * would after a scan. [availability] reports [state]; [install] plays [installStates] and leaves the module Ready
 * when they end in Ready. [nextResult] replaces the next scan's result once (Cancelled, Failed...).
 */
class FakeScannerLauncher(
    private val staging: StagingArea,
    var pages: List<OcrSample> = listOf(OcrSample.EN_HI),
) : ScannerLauncher {
    @Volatile var state: ModuleState = ModuleState.Ready

    var installStates: List<ModuleState> = listOf(
        ModuleState.Checking,
        ModuleState.Downloading(0, MODULE_BYTES),
        ModuleState.Downloading(MODULE_BYTES, MODULE_BYTES),
        ModuleState.Installing,
        ModuleState.Ready,
    )

    var nextResult: ScanResult? = null

    private val _requests = mutableListOf<ScanRequest>()
    val requests: List<ScanRequest> get() = _requests.toList()

    override suspend fun availability(): ModuleState = state

    override fun install(): Flow<ModuleState> = flow {
        for (step in installStates) {
            if (step == ModuleState.Ready || step is ModuleState.Unsupported) state = step
            emit(step)
        }
    }

    override suspend fun start(activity: Activity, request: ScanRequest): ScanStart {
        _requests += request
        val current = state
        if (current != ModuleState.Ready) return ScanStart.Unavailable(current)
        nextResult?.let {
            nextResult = null
            return ScanStart.Immediate(it)
        }
        val wanted = request.pageLimit?.let { pages.take(it) } ?: pages
        return ScanStart.Immediate(ScanResult.Pages(wanted.map { it.stage(staging, PageOrigin.SCANNER) }))
    }

    /** Never reached: this scanner has no screen to return from. */
    override suspend fun finish(resultCode: Int, data: Intent?): ScanResult =
        if (resultCode == Activity.RESULT_CANCELED) ScanResult.Cancelled else ScanResult.Failed("NoScannerScreen")

    private companion object {
        const val MODULE_BYTES = 4_000_000L
    }
}
