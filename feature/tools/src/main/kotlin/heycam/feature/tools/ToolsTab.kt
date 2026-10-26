package heycam.feature.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavGraphBuilder
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.HeycamDialog
import heycam.core.designsystem.HeycamIcons
import heycam.core.designsystem.HeycamSize
import heycam.core.designsystem.ToolItem
import heycam.core.designsystem.ToolSection
import heycam.core.designsystem.ToolsList
import heycam.core.designsystem.focusRing

/** MVP rows only list tools that work today. v1.1 adds merge/split/protect/unlock/page numbers/QR/ID card/book/PDF import. */
enum class Tool { SCAN_TO_PDF, IMPORT_PHOTOS, COMPRESS, EXTRACT_TEXT, PRINT, HEYPDF_LINK }

internal const val HEYPDF_URL = "https://heypdf.edgr.in"

/** The numbered rows, in order. The heypdf link is the footer, not a numbered tool. */
internal val NumberedTools = listOf(Tool.SCAN_TO_PDF, Tool.IMPORT_PHOTOS, Tool.COMPRESS, Tool.EXTRACT_TEXT, Tool.PRINT)

/**
 * The Tools tab (S12). Fills the space Home gives it and scrolls itself. Every numbered row goes to [onTool];
 * [Tool.HEYPDF_LINK] never does: the footer shows the P9 disclosure and opens the browser itself, so heycam never
 * sends anything there (PLAN P1, P9).
 */
@Composable
fun ToolsTab(onTool: (Tool) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var confirmHeypdf by rememberSaveable { mutableStateOf(false) }
    ToolsList(
        sections = listOf(
            ToolSection(title = null, items = NumberedTools.map { tool -> ToolItem(stringResource(tool.labelRes), { onTool(tool) }) }),
        ),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        footer = { HeypdfRow(onClick = { confirmHeypdf = true }) },
    )
    if (confirmHeypdf) {
        HeycamDialog(
            title = stringResource(R.string.tools_heypdf_title),
            text = stringResource(R.string.tools_heypdf_disclosure),
            confirmLabel = stringResource(R.string.tools_open),
            onConfirm = {
                confirmHeypdf = false
                openHeypdf(context)
            },
            onDismissRequest = { confirmHeypdf = false },
            dismissLabel = stringResource(R.string.tools_cancel),
        )
    }
}

/** Only the browser talks to heypdf; heycam hands it the address and nothing else. */
private fun openHeypdf(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, HEYPDF_URL.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.tools_no_browser, Toast.LENGTH_SHORT).show()
    }
}

/** The ".web" box of the tools mockup: sheet stock with a 2 dp ink bar on the leading edge. */
@Composable
private fun HeypdfRow(onClick: () -> Unit) {
    val c = Heycam.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.settingsRow)
            .background(c.sheet)
            .drawBehind {
                val bar = HeycamSize.ruleMedium.toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) bar / 2 else size.width - bar / 2
                drawLine(c.ink, Offset(x, 0f), Offset(x, size.height), bar)
            }
            .focusRing()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(HeycamIcons.OpenExternal, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
        Text(
            text = stringResource(R.string.tools_heypdf),
            style = Heycam.type.body,
            color = c.ink,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
    }
}

private val Tool.labelRes: Int
    get() = when (this) {
        Tool.SCAN_TO_PDF -> R.string.tools_scan_to_pdf
        Tool.IMPORT_PHOTOS -> R.string.tools_import_photos
        Tool.COMPRESS -> R.string.tools_compress
        Tool.EXTRACT_TEXT -> R.string.tools_extract_text
        Tool.PRINT -> R.string.tools_print
        Tool.HEYPDF_LINK -> R.string.tools_heypdf
    }

/**
 * No MVP tool needs a screen of its own: every row hands off to Scan, Import, the document picker in :app, the export
 * sheet or the text view. v1.1 tool flows (merge picker, split ranges, ...) register their destinations here.
 */
fun NavGraphBuilder.toolsGraph() = Unit
