package heycam.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import heycam.core.designsystem.ArticleTopBar
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.HeycamIcons
import heycam.core.designsystem.HeycamSize
import heycam.core.designsystem.focusRing
import heycam.core.designsystem.R as DesignR

/*
 * The settings pages on :core:designsystem: its top bar, switch, segmented control and dialogs, plus the three row
 * shapes of the settings mockup (set-head, set-row, set-row stack), which sit inside the page's 16 dp margins.
 */

/**
 * A screen of settings: the Broadsheet top bar, then a centred column (640 dp at most) that scrolls. The app draws
 * edge to edge, so the bar pads for the top of [windowInsets] and the column for its sides and bottom: the last row
 * scrolls clear of the gesture handle or the 3-button bar.
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Heycam.colors.paper)) {
        ArticleTopBar(
            title = title,
            onBack = onBack,
            windowInsets = windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        )
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = HeycamSize.maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(start = HeycamSize.gutter, end = HeycamSize.gutter, bottom = 24.dp),
                content = content,
            )
        }
    }
}

/** Letter-spaced capitals over an ink rule; a heading for TalkBack, which hears the sentence-case source. */
@Composable
internal fun SectionHead(text: String) {
    val c = Heycam.colors
    Text(
        text = text.toUpperCase(Locale.current),
        style = Heycam.type.sectionHead,
        color = c.ink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .semantics {
                heading()
                contentDescription = text
            }
            .padding(bottom = 10.dp),
    )
    HorizontalDivider(thickness = HeycamSize.hair, color = c.ink)
}

@Composable
internal fun RowDivider() = HorizontalDivider(thickness = HeycamSize.hair, color = Heycam.colors.rule)

/** Label above a control (the mockup's stacked set-row). */
@Composable
internal fun StackedRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp)) {
        Text(label, style = Heycam.type.label, color = Heycam.colors.ink, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

/** A row that opens something: label, an optional mono value, then a chevron (or the open-in-browser mark). */
@Composable
internal fun LinkRow(
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    external: Boolean = false,
    enabled: Boolean = true,
    // What TalkBack says the row opens, for an external row that is not a web page.
    opensIn: String? = null,
) {
    val c = Heycam.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.settingsRow)
            .focusRing()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = Heycam.type.label,
            color = if (enabled) c.ink else c.muted,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        if (value != null) {
            Text(
                text = value,
                style = Heycam.type.figureStrong,
                color = c.muted,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Icon(
            imageVector = if (external) HeycamIcons.OpenExternal else HeycamIcons.ChevronEnd,
            contentDescription = if (external) opensIn ?: stringResource(DesignR.string.hc_opens_outside) else null,
            tint = c.muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Opens a web page in the browser. heycam itself never fetches anything (PLAN P1). */
internal fun openWebPage(context: Context, url: String) =
    launch(context, Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))

/** Starts a new email in the user's mail app; nothing is filled in but the address. */
internal fun writeEmail(context: Context, address: String) =
    launch(context, Intent(Intent.ACTION_SENDTO, "mailto:$address".toUri()))

private fun launch(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.settings_no_app, Toast.LENGTH_SHORT).show()
    }
}

internal const val PRIVACY_POLICY_URL = "https://heycam.edgr.in/privacy/"
internal const val SOURCE_URL = "https://github.com/uroy80/heycam"
internal const val SUPPORT_EMAIL = "support@edgr.in"
