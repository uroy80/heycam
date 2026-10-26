package heycam.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Home's masthead: the nameplate centred between Search (start) and Settings (end). */
@Composable
fun Masthead(
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.statusBars,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Heycam.colors.paper)
            .windowInsetsPadding(windowInsets)
            .heightIn(min = HeycamSize.masthead)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(HeycamIcons.Search, stringResource(R.string.hc_search), onSearch)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Nameplate(Modifier.semantics { heading() })
        }
        IconButton(HeycamIcons.Settings, stringResource(R.string.hc_settings), onSettings)
    }
}

/**
 * The line under the masthead, between a 3 dp ink rule and an ink hairline: where the scans live, the counts in mono
 * and the date. Above 1.3x font scale the counts drop out and the two ends may wrap onto a second line.
 */
@Composable
fun Dateline(
    end: String,
    modifier: Modifier = Modifier,
    start: String = stringResource(R.string.hc_on_this_phone),
    middle: String? = null,
    middleDescription: String? = null,
) {
    val c = Heycam.colors
    val roomy = LocalDensity.current.fontScale <= 1.3f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.dateline)
            .drawBehind {
                val thick = HeycamSize.ruleThick.toPx()
                val hair = HeycamSize.hair.toPx()
                drawLine(c.ink, Offset(0f, thick / 2), Offset(size.width, thick / 2), thick)
                drawLine(c.ink, Offset(0f, size.height - hair / 2), Offset(size.width, size.height - hair / 2), hair)
            }
            .padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            Icon(HeycamIcons.Check, contentDescription = null, tint = c.ink, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            CapsText(start, Heycam.type.dateline, c.ink, maxLines = if (roomy) 1 else 2)
        }
        if (middle != null && roomy) {
            CapsText(
                text = middle,
                style = Heycam.type.figure.copy(fontSize = 10.5.sp, lineHeight = 12.sp),
                color = c.muted,
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .then(if (middleDescription != null) Modifier.semantics { contentDescription = middleDescription } else Modifier),
            )
        }
        CapsText(
            end,
            Heycam.type.dateline.copy(fontWeight = Heycam.type.kicker.fontWeight),
            c.ink,
            maxLines = if (roomy) 1 else 2,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Serif section tabs (Home's Contents | Tools). The chosen tab is italic with a 3 dp ink underline; a 1 dp ink rule
 * runs under the row. The tabs scroll sideways at large font sizes. [trailing] holds an end-aligned control (sort).
 */
@Composable
fun SectionTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Heycam.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.tabRow)
            .drawBehind {
                val hair = HeycamSize.hair.toPx()
                drawLine(c.ink, Offset(0f, size.height - hair / 2), Offset(size.width, size.height - hair / 2), hair)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            tabs.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    modifier = Modifier
                        .focusRing()
                        .widthIn(min = HeycamSize.touch)
                        .heightIn(min = HeycamSize.tabRow)
                        .drawBehind {
                            if (on) {
                                val w = HeycamSize.ruleThick.toPx()
                                drawLine(c.ink, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                            }
                        }
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(label, style = if (on) Heycam.type.tabSelected else Heycam.type.tab, color = if (on) c.ink else c.muted, maxLines = 1)
                }
            }
        }
        trailing?.invoke()
    }
}

/**
 * The top bar for every screen but Home: back, a serif title on one line and up to two actions, over an ink hairline.
 * With a [kicker] the bar is 72 dp and the kicker sits above the title.
 */
@Composable
fun ArticleTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = WindowInsets.statusBars,
) {
    val c = Heycam.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.paper)
            .windowInsetsPadding(windowInsets)
            .drawBehind {
                val hair = HeycamSize.hair.toPx()
                drawLine(c.ink, Offset(0f, size.height - hair / 2), Offset(size.width, size.height - hair / 2), hair)
            }
            .heightIn(min = if (kicker != null) HeycamSize.appBarWithKicker else HeycamSize.appBar)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(HeycamIcons.Back, stringResource(R.string.hc_back), onBack)
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            if (kicker != null) Kicker(kicker)
            Text(
                text = title,
                style = Heycam.type.title,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        actions()
    }
}
