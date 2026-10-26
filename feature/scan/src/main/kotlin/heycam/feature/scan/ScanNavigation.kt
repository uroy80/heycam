package heycam.feature.scan

import android.content.res.Resources
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import heycam.core.model.ImportTarget
import heycam.core.scanner.ScanRequest
import heycam.feature.scan.entry.HostedScanEntry
import heycam.feature.scan.entry.ScanEntryEffects
import heycam.feature.scan.entry.ScanEntryViewModel
import heycam.feature.scan.entry.ScannerStatusContent
import heycam.feature.scan.review.ReviewDestination
import kotlinx.serialization.Serializable

/** Review (filters) screen for a persisted ScanSession: page large, filter strip, apply-to-all, Add page, Save. */
@Serializable data class ReviewRoute(val sessionId: String)

/** Hoisted once above the NavHost (it owns the ActivityResult launchers), handed to screens as lambdas. */
interface ScanEntry {
    fun scan(target: ImportTarget, request: ScanRequest = ScanRequest())
    fun importPhotos(target: ImportTarget)
    fun importShared(uris: List<Uri>)
}

sealed interface ScanMessage {
    /** [reason] is short, localised text. */
    data class ScannerUnavailable(val reason: String) : ScanMessage
    data class Rejected(val count: Int) : ScanMessage
    /** Scanning or importing failed; [reason] is short, localised text. */
    data class Failed(val reason: String) : ScanMessage
}

/** One localised line for a snackbar. */
fun ScanMessage.text(resources: Resources): String = when (this) {
    is ScanMessage.ScannerUnavailable -> reason
    is ScanMessage.Rejected -> resources.getQuantityString(R.plurals.scan_rejected, count, count)
    is ScanMessage.Failed -> reason
}

/**
 * The app's single scan entry. Call it above the NavHost, where the view-model owner is the Activity, so the
 * launchers and the pending target survive navigation and process death.
 */
@Composable
fun rememberScanEntry(onStaged: (sessionId: String) -> Unit, onMessage: (ScanMessage) -> Unit): ScanEntry {
    val viewModel: ScanEntryViewModel = hiltViewModel()
    ScanEntryEffects(viewModel, onStaged, onMessage)
    return remember(viewModel) { HostedScanEntry(viewModel) }
}

/**
 * S6, for Home's `scanStatus` slot: the one-time scanner download with progress and Retry, or a plain line when
 * this phone cannot scan. Shows nothing while the scanner is ready.
 */
@Composable
fun ScannerStatusBar(entry: ScanEntry, modifier: Modifier = Modifier) {
    val viewModel = (entry as? HostedScanEntry)?.viewModel ?: return
    val state by viewModel.scanner.collectAsStateWithLifecycle()
    ScannerStatusContent(state, onRetry = viewModel::retryScanner, onDismiss = viewModel::dismissScanner, modifier = modifier)
}

fun NavController.navigateToReview(sessionId: String) = navigate(ReviewRoute(sessionId))

/**
 * Review. Saving a new document pops Review and calls [onSaved]; saving added or retaken pages just pops back to
 * the document they belong to, which is already below. Discarding (or a session that no longer exists) calls
 * [onDiscarded].
 */
fun NavGraphBuilder.scanGraph(
    navController: NavController,
    onSaved: (documentId: Long) -> Unit,
    onDiscarded: () -> Unit,
) {
    composable<ReviewRoute> { entry ->
        val onTop = { navController.currentBackStackEntry?.id == entry.id }
        ReviewDestination(
            onSaved = { documentId, newDocument ->
                if (onTop()) {
                    navController.popBackStack()
                    if (newDocument) onSaved(documentId.value)
                }
            },
            onClosed = { if (onTop()) onDiscarded() },
        )
    }
}
