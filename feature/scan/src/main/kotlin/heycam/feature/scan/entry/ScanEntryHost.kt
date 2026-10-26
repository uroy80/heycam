package heycam.feature.scan.entry

import android.content.ActivityNotFoundException
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import heycam.core.model.ImportTarget
import heycam.core.model.UnsupportedReason
import heycam.core.scanner.ScanRequest
import heycam.feature.scan.R
import heycam.feature.scan.ScanEntry
import heycam.feature.scan.ScanMessage

/** Photo Picker limit for one import (F2). */
internal const val MAX_PICK = 50

/** The real [ScanEntry]: forwards to the activity-scoped view model whose events [ScanEntryEffects] carries out. */
internal class HostedScanEntry(val viewModel: ScanEntryViewModel) : ScanEntry {
    override fun scan(target: ImportTarget, request: ScanRequest) = viewModel.scan(target, request)
    override fun importPhotos(target: ImportTarget) = viewModel.importPhotos(target)
    override fun importShared(uris: List<Uri>) = viewModel.importShared(uris)
}

/** Owns the scanner and Photo Picker launchers and turns the view model's events into launches and callbacks. */
@Composable
internal fun ScanEntryEffects(
    viewModel: ScanEntryViewModel,
    onStaged: (sessionId: String) -> Unit,
    onMessage: (ScanMessage) -> Unit,
) {
    val activity = LocalActivity.current
    val resources = LocalResources.current
    val staged by rememberUpdatedState(onStaged)
    val message by rememberUpdatedState(onMessage)
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onScannerResult(it.resultCode, it.data)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) {
        viewModel.onPhotosPicked(it)
    }
    LaunchedEffect(viewModel, activity) {
        viewModel.events.collect { event ->
            when (event) {
                is EntryEvent.StartScanner -> {
                    val host = activity ?: return@collect viewModel.onLaunchFailed()
                    val request = viewModel.start(host, event.request) ?: return@collect
                    try {
                        scanner.launch(request)
                    } catch (e: ActivityNotFoundException) {
                        viewModel.onLaunchFailed()
                    }
                }
                EntryEvent.PickPhotos -> try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    viewModel.onPickerOpened()
                } catch (e: ActivityNotFoundException) {
                    viewModel.onLaunchFailed()
                }
                is EntryEvent.Staged -> staged(event.sessionId)
                is EntryEvent.Notice -> message(event.notice.toMessage(resources))
            }
        }
    }
}

internal fun EntryNotice.toMessage(resources: Resources): ScanMessage = when (this) {
    is EntryNotice.Unavailable -> ScanMessage.ScannerUnavailable(resources.getString(reason.label))
    is EntryNotice.Rejected -> ScanMessage.Rejected(count)
    EntryNotice.Failed -> ScanMessage.Failed(resources.getString(R.string.scan_failed))
}

/** Why scanning is off on this phone, in one short line. */
internal val UnsupportedReason.label: Int
    get() = when (this) {
        UnsupportedReason.NO_PLAY_SERVICES -> R.string.scan_unavailable_no_play_services
        UnsupportedReason.PLAY_SERVICES_OUTDATED -> R.string.scan_unavailable_outdated
        UnsupportedReason.LOW_RAM -> R.string.scan_unavailable_low_ram
    }
