package heycam.feature.library.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import heycam.core.designsystem.EmptyState
import heycam.core.designsystem.HeycamIcons
import heycam.core.designsystem.HeycamMenu
import heycam.core.designsystem.Note
import heycam.core.designsystem.PrimaryButton
import heycam.core.designsystem.ReportBottomBar
import heycam.core.designsystem.SecondaryButton
import heycam.core.designsystem.SectionTabs
import heycam.core.model.LibraryStats
import heycam.core.model.SortOrder
import heycam.feature.library.R
import heycam.feature.library.ui.BottomBarInsets
import heycam.feature.library.ui.InkIconButton
import heycam.feature.library.ui.Paper
import heycam.feature.library.ui.TopBarInsets
import heycam.feature.library.ui.Type

/** Above this font scale the chrome drops what is decorative (the dateline's counts, the sort label). */
internal const val LARGE_FONT_SCALE = 1.3f

/** Below this window width the sort control is its icon alone. */
private val SORT_LABEL_MIN_WIDTH = 360.dp

/**
 * Search · "heycam :)" · Settings. The nameplate is a logo, so it stops growing at 1.3x font scale. [padForBars] is
 * false when the masthead scrolls with the contents, below an already padded status bar.
 */
@Composable
internal fun Masthead(onSearch: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier, padForBars: Boolean = true) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (padForBars) Modifier.windowInsetsPadding(TopBarInsets) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkIconButton(R.drawable.library_ic_search, stringResource(R.string.library_search), onSearch)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(LARGE_FONT_SCALE))) {
                Text(
                    stringResource(R.string.library_nameplate),
                    style = Type.nameplate,
                    color = Paper.ink,
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = "heycam"; heading() },
                )
            }
        }
        InkIconButton(R.drawable.library_ic_settings, stringResource(R.string.library_settings), onSettings)
    }
}

/**
 * "✓ On this phone · 42 docs · 318 pp · Tue 20 Oct" between a thick ink rule and a hairline. The counts show only
 * as far as they fit: "42 DOCS · 318 PP", then "42 DOCS", then nothing, never a cut-off figure. Above 1.3x font
 * scale they drop out and the place and the date take a line each. TalkBack always hears the whole line in words.
 * [onTextLayout] and [onCounts] let tests see what was laid out.
 */
@Composable
internal fun Dateline(
    stats: LibraryStats,
    date: String,
    spokenDate: String,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onCounts: (String?) -> Unit = {},
) {
    val ink = Paper.ink
    val docs = pluralStringResource(R.plurals.library_docs, stats.documents, stats.documents)
    val pages = pluralStringResource(R.plurals.library_pp, stats.pages, stats.pages)
    val counts = if (stats.pages > 0) listOf("$docs · $pages", docs) else listOf(docs)
    val spokenDocs = pluralStringResource(R.plurals.library_documents_spoken, stats.documents, stats.documents)
    val spokenPages = pluralStringResource(R.plurals.library_pages, stats.pages, stats.pages)
    val here = stringResource(R.string.library_on_this_phone)
    val spoken = listOfNotNull(here, spokenDocs, spokenPages.takeIf { stats.pages > 0 }, spokenDate).joinToString(", ")
    val roomy = LocalDensity.current.fontScale <= LARGE_FONT_SCALE
    val rules = modifier
        .fillMaxWidth()
        .heightIn(min = 26.dp)
        .drawBehind {
            drawLine(ink, Offset(0f, 0f), Offset(size.width, 0f), 3.dp.toPx())
            drawLine(ink, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        .padding(horizontal = 16.dp, vertical = 5.dp)
        .clearAndSetSemantics { contentDescription = spoken }
    val place = @Composable { textModifier: Modifier ->
        Icon(painterResource(R.drawable.library_ic_check), null, tint = Paper.ink, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            here.toUpperCase(Locale.current),
            style = Type.dateline,
            color = Paper.ink,
            maxLines = if (roomy) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = onTextLayout,
            modifier = textModifier,
        )
    }
    val dateText = @Composable { textModifier: Modifier, align: TextAlign ->
        Text(
            date.toUpperCase(Locale.current),
            style = Type.dateline.copy(fontWeight = Type.kicker.fontWeight),
            color = Paper.ink,
            textAlign = align,
            maxLines = if (roomy) 1 else 2,
            onTextLayout = onTextLayout,
            modifier = textModifier,
        )
    }
    if (roomy) {
        Row(rules, verticalAlignment = Alignment.CenterVertically) {
            place(Modifier)
            FirstThatFits(
                candidates = counts.map { it.toUpperCase(Locale.current) },
                style = Type.figure.copy(fontSize = 10.5.sp, lineHeight = 12.sp),
                color = Paper.muted,
                onChosen = onCounts,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            dateText(Modifier.padding(start = 8.dp), TextAlign.End)
        }
    } else {
        // Large text: the place and the date each get their own line, so the two caps labels never run together.
        Column(rules) {
            Row(verticalAlignment = Alignment.CenterVertically) { place(Modifier.weight(1f, fill = false)) }
            dateText(Modifier.padding(start = 16.dp), TextAlign.Start)
        }
    }
}

/**
 * Shows the first of [candidates] (longest first) that fits on one line in the width it is given, centred, or
 * nothing when none fits. It keeps the height of one line either way, so the row does not jump.
 */
@Composable
private fun FirstThatFits(
    candidates: List<String>,
    style: TextStyle,
    color: Color,
    onChosen: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosen by rememberUpdatedState(onChosen)
    Layout(
        content = { candidates.forEach { Text(it, style = style, color = color, maxLines = 1, softWrap = false) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
        val fit = placeables.indexOfFirst { it.width <= constraints.maxWidth }
        chosen(candidates.getOrNull(fit))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.getOrNull(fit)?.width ?: 0
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            placeables.getOrNull(fit)?.let { it.place((width - it.width) / 2, (height - it.height) / 2) }
        }
    }
}

internal enum class HomeTab { CONTENTS, TOOLS }

/**
 * Contents | Tools in serif; the chosen tab is italic with a 3 dp ink underline. The tabs take what is left after
 * [trailing] and scroll sideways at large font sizes, so the sort and overflow controls are never squeezed.
 */
@Composable
internal fun HomeTabs(selected: HomeTab, onSelect: (HomeTab) -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    SectionTabs(
        tabs = listOf(stringResource(R.string.library_tab_contents), stringResource(R.string.library_tab_tools)),
        selected = selected.ordinal,
        onSelect = { onSelect(HomeTab.entries[it]) },
        modifier = modifier.padding(horizontal = 16.dp),
        trailing = trailing,
    )
}

internal val SortOrder.label: Int
    get() = when (this) {
        SortOrder.UPDATED_DESC -> R.string.library_sort_recent
        SortOrder.CREATED_DESC -> R.string.library_sort_newest
        SortOrder.TITLE_ASC -> R.string.library_sort_name
        SortOrder.SIZE_DESC -> R.string.library_sort_size
    }

/**
 * "Sort: Recent" opens the four orders; the current one carries a check. Only the icon shows at large font sizes and
 * on narrow windows (the largest display size), so the Contents and Tools tabs keep their room.
 */
@Composable
internal fun SortControl(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val sortBy = stringResource(R.string.library_sort_by, stringResource(sort.label))
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val showLabel = density.fontScale <= LARGE_FONT_SCALE && windowWidth >= SORT_LABEL_MIN_WIDTH
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = sortBy },
        ) {
            Icon(painterResource(R.drawable.library_ic_sort), null, tint = Paper.ink, modifier = Modifier.size(18.dp))
            if (showLabel) {
                Spacer(Modifier.width(6.dp))
                Text(stringResource(sort.label), style = Type.label, color = Paper.ink, maxLines = 1)
            }
        }
        HeycamMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(stringResource(order.label)) },
                    onClick = { open = false; onSort(order) },
                    trailingIcon = if (order == sort) {
                        { Icon(painterResource(R.drawable.library_ic_check), null, tint = Paper.accent) }
                    } else null,
                    modifier = Modifier.semantics { selected = order == sort },
                )
            }
        }
    }
}

/** Overflow: New folder, Trash. */
@Composable
internal fun ContentsMenu(onNewFolder: () -> Unit, onTrash: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        InkIconButton(R.drawable.library_ic_more_vert, stringResource(R.string.library_more), { open = true })
        HeycamMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_new_folder)) },
                leadingIcon = { Icon(painterResource(R.drawable.library_ic_folder), null, tint = Paper.ink) },
                onClick = { open = false; onNewFolder() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_trash)) },
                leadingIcon = { Icon(painterResource(R.drawable.library_ic_delete), null, tint = Paper.ink) },
                onClick = { open = false; onTrash() },
            )
        }
    }
}

/** Bottom bar: square Import + ink Scan block; [modifier] keeps the buttons in the content column on wide screens. */
@Composable
internal fun ScanBar(onImport: () -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val ink = Paper.ink
    // Its height reaches the app shell, whose scan messages then print above Scan and Import.
    ReportBottomBar {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Paper.paper)
                .drawBehind { drawLine(ink, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .windowInsetsPadding(BottomBarInsets),
        ) {
            Row(modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, Paper.ink),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(painterResource(R.drawable.library_ic_image), stringResource(R.string.library_import), tint = Paper.ink)
                }
                Button(
                    onClick = onScan,
                    shape = RoundedCornerShape(2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Paper.ink, contentColor = Paper.onInk),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    Icon(painterResource(R.drawable.library_ic_camera), null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.library_scan), style = Type.button.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** First run (S2): the empty picture box, one headline, Scan and Import, and the on-device note. */
@Composable
internal fun FirstRun(onScan: () -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        kicker = stringResource(R.string.library_empty_kicker),
        headline = stringResource(R.string.library_empty_title),
        modifier = modifier,
        actions = {
            PrimaryButton(
                stringResource(R.string.library_scan_document),
                onScan,
                Modifier.fillMaxWidth(),
                icon = HeycamIcons.Camera,
                large = true,
            )
            SecondaryButton(stringResource(R.string.library_import_photos), onImport, Modifier.fillMaxWidth(), icon = HeycamIcons.Gallery)
        },
        footer = { Note(stringResource(R.string.library_stays_on_phone)) },
    )
}
