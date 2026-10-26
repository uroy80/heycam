package heycam.core.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A named action offered to TalkBack (and usually also in a menu): Share, Move, Rename, Delete. */
@Immutable
data class RowAction(val label: String, val onClick: () -> Unit)

private fun List<RowAction>.toCustomActions() = map { a -> CustomAccessibilityAction(a.label) { a.onClick(); true } }

/**
 * A contents entry: thumbnail, serif title (2 lines at most), mono meta line with flags, and the page count as a
 * folio at the end. TalkBack hears one sentence ("title, 8 pages, meta") with [actions] as custom actions; a long
 * press calls [onLongClick] (select). Selected rows get an accent wash, a 3 dp accent bar at the start edge and a
 * check over the thumbnail.
 *
 * @param meta the visible meta line, e.g. "Tue 20 Oct · 14:32 · 2.4 MB".
 * @param spokenMeta what TalkBack reads instead of [meta], e.g. "Tuesday 20 October, 14:32, 2.4 megabytes".
 * @param selectionMode while true, TalkBack also reports "not selected" rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentRow(
    title: String,
    meta: String,
    pageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: (@Composable BoxScope.() -> Unit)? = null,
    flags: List<String> = emptyList(),
    selected: Boolean = false,
    selectionMode: Boolean = selected,
    onLongClick: (() -> Unit)? = null,
    actions: List<RowAction> = emptyList(),
    spokenMeta: String? = null,
) {
    val c = Heycam.colors
    val pages = pluralStringResource(R.plurals.hc_pages, pageCount, pageCount)
    val sentence = (listOf(title, pages, spokenMeta ?: meta.replace(" · ", ", ")) + flags).joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.listRow)
            .background(if (selected) c.accentWash else c.paper)
            .drawBehind {
                val hair = HeycamSize.hair.toPx()
                drawLine(c.rule, Offset(0f, size.height - hair / 2), Offset(size.width, size.height - hair / 2), hair)
                if (selected) {
                    val w = HeycamSize.ruleThick.toPx()
                    val x = if (layoutDirection == LayoutDirection.Ltr) w / 2 else size.width - w / 2
                    drawLine(c.accent, Offset(x, 0f), Offset(x, size.height), w)
                }
            }
            .focusRing()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) stringResource(R.string.hc_select) else null,
            )
            .semantics {
                contentDescription = sentence
                if (selectionMode) this.selected = selected
                if (actions.isNotEmpty()) customActions = actions.toCustomActions()
            }
            .padding(horizontal = HeycamSize.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clearAndSetSemantics {}) {
            Thumb(HeycamSize.thumbRow.width, HeycamSize.thumbRow.height, thumbnail)
            if (selected) {
                Box(
                    Modifier.align(Alignment.Center).size(24.dp).background(c.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(HeycamIcons.Check, contentDescription = null, tint = c.onInk, modifier = Modifier.size(18.dp)) }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
                .clearAndSetSemantics {},
        ) {
            Text(title, style = Heycam.type.rowTitle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(meta, style = Heycam.type.figure, color = c.muted, modifier = Modifier.align(Alignment.CenterVertically))
                flags.forEach { Flag(it, Modifier.align(Alignment.CenterVertically)) }
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.clearAndSetSemantics {}) {
            Text("$pageCount", style = Heycam.type.folio, color = c.ink)
            CapsText(stringResource(R.string.hc_pp), Heycam.type.kicker.copy(letterSpacing = Heycam.type.dateline.letterSpacing), c.muted)
        }
    }
}

/** A page-shaped frame on sheet stock with a control-boundary border; shows [content] or a skeleton. */
@Composable
internal fun Thumb(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, content: (@Composable BoxScope.() -> Unit)?) {
    val c = Heycam.colors
    Box(
        Modifier
            .size(width, height)
            .background(c.sheet)
            .border(HeycamSize.hair, c.line),
    ) {
        if (content != null) content() else PageSkeleton(Modifier.fillMaxSize())
    }
}

/**
 * The front-page lead: the latest scan with an accent "Latest" kicker, a large thumbnail, the title as a headline, a
 * mono meta line, flags, and Open · Share.
 */
@Composable
fun LeadStory(
    title: String,
    meta: String,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: (@Composable BoxScope.() -> Unit)? = null,
    flags: List<String> = emptyList(),
    kicker: String = stringResource(R.string.hc_latest),
    spokenMeta: String? = null,
) {
    val c = Heycam.colors
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
            Kicker(kicker)
            HorizontalDivider(Modifier.weight(1f).padding(start = 10.dp), thickness = HeycamSize.hair, color = c.rule)
        }
        Spacer(Modifier.height(10.dp))
        Row {
            // The headline is the accessible target; the picture is a second touch target for the same action.
            Box(Modifier.pointerInput(onOpen) { detectTapGestures { onOpen() } }) {
                Thumb(HeycamSize.thumbLead.width, HeycamSize.thumbLead.height, thumbnail)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Column(
                    Modifier
                        .focusRing()
                        .clickable(onClick = onOpen, role = Role.Button)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        title,
                        style = Heycam.type.title,
                        color = c.ink,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        meta,
                        style = Heycam.type.figure,
                        color = c.muted,
                        modifier = if (spokenMeta != null) Modifier.semantics { contentDescription = spokenMeta } else Modifier,
                    )
                    if (flags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            flags.forEach { Flag(it) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(stringResource(R.string.hc_open), onOpen)
                    TextButton(stringResource(R.string.hc_share), onShare)
                }
            }
        }
    }
}

/**
 * A search hit inside recognised text: the document title in serif, a snippet of at most two lines in reading type
 * with the [match] (character range in [snippet]) printed in reverse, and the page as "p. 3" in mono. TalkBack hears
 * "title, page 3, snippet".
 */
@Composable
fun TextHitRow(
    title: String,
    snippet: String,
    match: IntRange?,
    pageNumber: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Heycam.colors
    val spokenPage = stringResource(R.string.hc_page_number, pageNumber)
    val annotated = remember(snippet, match, c) {
        buildAnnotatedString {
            append(snippet)
            if (match != null) {
                val start = match.first.coerceIn(0, snippet.length)
                val end = (match.last + 1).coerceIn(start, snippet.length)
                if (end > start) addStyle(SpanStyle(color = c.paper, background = c.ink), start, end)
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.listRow)
            .drawBehind {
                val hair = HeycamSize.hair.toPx()
                drawLine(c.rule, Offset(0f, size.height - hair / 2), Offset(size.width, size.height - hair / 2), hair)
            }
            .focusRing()
            .clickable(onClick = onClick)
            .semantics { contentDescription = listOf(title, spokenPage, snippet).joinToString(", ") }
            .padding(horizontal = HeycamSize.gutter, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics {}) {
            Text(title, style = Heycam.type.rowTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                annotated,
                style = Heycam.type.reading.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = c.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.hc_page_abbrev, pageNumber),
            style = Heycam.type.figure,
            color = c.muted,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp).clearAndSetSemantics {},
        )
    }
}

/**
 * A settings, about or storage line: a label, an optional [value] in mono at the end and, when it opens something,
 * a chevron (or the external-link icon when it leaves the app). One TalkBack node: "label, value".
 *
 * @param spokenValue what TalkBack reads instead of [value], e.g. "182 megabytes" for "182 MB".
 */
@Composable
fun ValueRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    spokenValue: String? = null,
    onClick: (() -> Unit)? = null,
    external: Boolean = false,
) {
    val c = Heycam.colors
    val opensOutside = stringResource(R.string.hc_opens_outside)
    val sentence = listOfNotNull(label, spokenValue ?: value, if (external) opensOutside else null).joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.settingsRow)
            .drawBehind {
                val hair = HeycamSize.hair.toPx()
                drawLine(c.rule, Offset(0f, size.height - hair / 2), Offset(size.width, size.height - hair / 2), hair)
            }
            .then(if (onClick != null) Modifier.focusRing().clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = sentence }
            .padding(horizontal = HeycamSize.gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Heycam.type.label, color = c.ink, modifier = Modifier.weight(1f).clearAndSetSemantics {})
        if (value != null) {
            Text(
                value,
                style = Heycam.type.figureStrong,
                color = c.muted,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp).clearAndSetSemantics {},
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = if (external) HeycamIcons.OpenExternal else HeycamIcons.ChevronEnd,
                contentDescription = null,
                tint = c.ink,
                modifier = Modifier.padding(start = 8.dp).size(20.dp),
            )
        }
    }
}

/** One entry in [ToolsList]. [external] marks a link that leaves the app (it shows an "opens outside" icon). */
@Immutable
data class ToolItem(val title: String, val onClick: () -> Unit, val hint: String? = null, val external: Boolean = false)

/** A group of tools under a [SectionHeader]; a null title draws the group without a head. */
@Immutable
data class ToolSection(val title: String?, val items: List<ToolItem>)

/** A numbered tool row: mono index, serif title, optional hint and a chevron (or an external-link icon). */
@Composable
fun ToolRow(
    number: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    external: Boolean = false,
) {
    val c = Heycam.colors
    val opensOutside = stringResource(R.string.hc_opens_outside)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.toolRow)
            .drawBehind {
                val hair = HeycamSize.hair.toPx()
                drawLine(c.rule, Offset(0f, size.height - hair / 2), Offset(size.width, size.height - hair / 2), hair)
            }
            .focusRing()
            .clickable(role = if (external) Role.Button else null, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = HeycamSize.gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number.toString().padStart(2, '0'),
            style = Heycam.type.frameNo,
            color = c.muted,
            modifier = Modifier.width(36.dp).clearAndSetSemantics {},
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = Heycam.type.rowTitle, color = c.ink)
            if (hint != null) Text(hint, style = Heycam.type.hint, color = c.muted)
        }
        Icon(
            imageVector = if (external) HeycamIcons.OpenExternal else HeycamIcons.ChevronEnd,
            contentDescription = if (external) opensOutside else null,
            tint = c.ink,
            modifier = Modifier.padding(start = 8.dp).size(20.dp),
        )
    }
}

/** Tools in numbered groups (01, 02, … across groups), with an optional footer such as a [Note]. */
@Composable
fun ToolsList(sections: List<ToolSection>, modifier: Modifier = Modifier, footer: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        var number = 0
        sections.forEachIndexed { s, section ->
            if (section.title != null) {
                SectionHeader(
                    section.title,
                    opensWithRule = s > 0,
                    modifier = Modifier.padding(horizontal = HeycamSize.gutter),
                )
            }
            section.items.forEach { item ->
                number += 1
                ToolRow(number, item.title, item.onClick, hint = item.hint, external = item.external)
            }
        }
        if (footer != null) {
            Box(Modifier.padding(HeycamSize.gutter)) { footer() }
        }
    }
}

/** A quiet italic line with a leading icon: honest notes ("Scans stay on this phone") and link footers. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, icon: ImageVector = HeycamIcons.Shield, onClick: (() -> Unit)? = null) {
    val c = Heycam.colors
    Row(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.focusRing().heightIn(min = HeycamSize.touch).clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Heycam.type.caption, color = if (onClick != null) c.ink else c.muted)
    }
}
