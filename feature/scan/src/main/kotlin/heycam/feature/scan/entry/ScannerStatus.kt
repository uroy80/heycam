package heycam.feature.scan.entry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import heycam.core.designsystem.Kicker
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.feature.scan.R
import heycam.feature.scan.ui.InkIconButton
import heycam.feature.scan.ui.InkTextButton
import heycam.feature.scan.ui.LoadBar
import heycam.feature.scan.ui.Paper
import heycam.feature.scan.ui.Type
import kotlin.math.roundToInt

/**
 * S6: the scanner module bar under Home's dateline. Downloading shows a LoadBar with progress; a failure offers
 * Retry; an unsupported phone says so plainly (Import still works from the scan bar). Nothing when ready.
 */
@Composable
internal fun ScannerStatusContent(
    state: ModuleState?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == null || state == ModuleState.Ready) return
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        when (state) {
            ModuleState.Checking, ModuleState.Installing, is ModuleState.Downloading -> {
                val fraction = (state as? ModuleState.Downloading)?.let { d ->
                    d.totalBytes?.takeIf { it > 0 }?.let { (d.bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Kicker(
                            stringResource(
                                if (state == ModuleState.Installing) R.string.scan_installing_scanner else R.string.scan_getting_scanner,
                            ),
                        )
                        Text(stringResource(R.string.scan_one_time), style = Type.body, color = Paper.muted)
                    }
                    if (fraction != null) {
                        Text(
                            stringResource(R.string.scan_percent, (fraction * 100).roundToInt()),
                            style = Type.figure,
                            color = Paper.ink,
                        )
                    }
                }
                LoadBar(fraction)
            }
            is ModuleState.Failed -> Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Kicker(stringResource(R.string.scan_get_failed))
                    if (state.reason == ModuleFailure.NO_NETWORK) {
                        Text(stringResource(R.string.scan_no_connection), style = Type.body, color = Paper.muted)
                    }
                }
                if (state.retryable) InkTextButton(stringResource(R.string.scan_retry), onRetry)
                InkIconButton(R.drawable.scan_ic_close, stringResource(R.string.scan_dismiss), onDismiss)
            }
            is ModuleState.Unsupported -> Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Kicker(stringResource(R.string.scan_scanner))
                    Text(
                        stringResource(state.reason.label),
                        style = Type.body,
                        color = Paper.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InkIconButton(R.drawable.scan_ic_close, stringResource(R.string.scan_dismiss), onDismiss)
            }
            ModuleState.Ready -> Unit
        }
        HorizontalDivider(thickness = 1.dp, color = Paper.rule)
    }
}
