package heycam.feature.scan.entry

import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import heycam.core.model.ModuleState
import heycam.core.scanner.ScanRequest
import heycam.core.scanner.ScanResult
import heycam.core.scanner.ScanStart
import heycam.core.scanner.ScannerLauncher
import kotlin.coroutines.cancellation.CancellationException

/** What to do after asking the scanner seam to start. */
internal sealed interface StartOutcome {
    /** Launch Google's scanner UI with this request. */
    data class Launch(val request: IntentSenderRequest) : StartOutcome
    /** The seam already has a result (fakes), or the start failed. */
    data class Finished(val result: ScanResult) : StartOutcome
    /** Not installed yet or not supported on this phone. */
    data class NotReady(val state: ModuleState) : StartOutcome
}

/** Availability check then start, shared by the scan entry and Review's "Add page". */
internal suspend fun ScannerLauncher.prepare(activity: Activity, request: ScanRequest): StartOutcome = try {
    when (val state = availability()) {
        ModuleState.Ready -> when (val start = start(activity, request)) {
            is ScanStart.Launch -> StartOutcome.Launch(start.request)
            is ScanStart.Immediate -> StartOutcome.Finished(start.result)
            is ScanStart.Unavailable -> StartOutcome.NotReady(start.state)
        }
        else -> StartOutcome.NotReady(state)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    StartOutcome.Finished(ScanResult.Failed(e.javaClass.simpleName))
}

/** Reads the scanner's activity result, never throwing. */
internal suspend fun ScannerLauncher.finishSafely(resultCode: Int, data: android.content.Intent?): ScanResult = try {
    finish(resultCode, data)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ScanResult.Failed(e.javaClass.simpleName)
}
