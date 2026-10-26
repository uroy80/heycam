package heycam.feature.document.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import heycam.core.designsystem.Kicker
import heycam.core.designsystem.Note
import heycam.core.designsystem.TextButton
import heycam.core.model.Page
import heycam.core.model.PageId
import heycam.core.model.PageText
import heycam.core.model.TextStatus
import heycam.feature.document.FindState
import heycam.feature.document.R
import heycam.feature.document.ui.EndOrBelow
import heycam.feature.document.ui.InkIconButton
import heycam.feature.document.ui.InkTextButton
import heycam.feature.document.ui.LoadBar
import heycam.feature.document.ui.PageThumb
import heycam.feature.document.ui.Paper
import heycam.feature.document.ui.Type

/** The page being corrected and its draft; null when not editing. */
internal data class TextEdit(val pageId: PageId, val draft: TextFieldValue)

internal data class TextPaneActions(
    val onQuery: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onCloseFind: () -> Unit = {},
    val onRetry: (PageId) -> Unit = {},
    /** Opens the editor on that page's text. */
    val onEdit: (PageId) -> Unit = {},
    val onDraft: (TextFieldValue) -> Unit = {},
)

/**
 * Text view (S10): find bar, page frames with match marks, the recognised text per page (each with its own Edit), and
 * the honest note at the end. While a page is being corrected only its field shows, so it keeps the room the
 * keyboard leaves; Save and Cancel are in the top bar.
 */
@Composable
internal fun TextPane(
    pages: List<Page>,
    texts: Map<PageId, PageText>,
    find: FindState,
    findOpen: Boolean,
    edit: TextEdit?,
    list: LazyListState,
    actions: TextPaneActions,
    modifier: Modifier = Modifier,
) {
    val layouts = remember { mutableStateMapOf<PageId, TextLayoutResult>() }
    val density = LocalDensity.current
    val matchPages = remember(find.matches) { find.matches.mapTo(HashSet()) { it.pageId } }
    val firstVisible by remember(list) { derivedStateOf { list.firstVisibleItemIndex } }
    val visiblePage = firstVisible.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val currentPage = find.currentMatch?.pageIndex ?: visiblePage

    // Bring the current match into view, a little below the top so its line has context.
    LaunchedEffect(find.currentMatch, find.query) {
        val match = find.currentMatch ?: return@LaunchedEffect
        val layout = layouts[match.pageId]
        val offset = layout?.let {
            val line = it.getLineForOffset(match.range.first.coerceIn(0, (it.layoutInput.text.length - 1).coerceAtLeast(0)))
            (it.getLineTop(line) + with(density) { (PAGE_HEADER - CONTEXT).toPx() }).toInt().coerceAtLeast(0)
        } ?: 0
        list.animateScrollToItem(match.pageIndex, offset)
    }
    // The page being corrected starts at the top, so its field is in view when the keyboard comes up.
    LaunchedEffect(edit?.pageId) {
        val id = edit?.pageId ?: return@LaunchedEffect
        val index = pages.indexOfFirst { it.id == id }
        if (index >= 0) list.animateScrollToItem(index)
    }

    Column(modifier) {
        if (edit == null) {
            if (findOpen) FindBar(find, actions)
            FrameStrip(pages, matchPages, currentPage, onOpen = { index -> list.requestScrollToItem(index) })
        }
        HorizontalDivider(thickness = 1.dp, color = Paper.rule)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(pages, key = { _, p -> p.id.value }) { index, page ->
                PageTextSection(
                    page = page,
                    index = index,
                    count = pages.size,
                    text = texts[page.id],
                    find = find,
                    edit = edit?.takeIf { it.pageId == page.id },
                    editing = edit != null,
                    actions = actions,
                    onLayout = { layouts[page.id] = it },
                )
            }
            if (edit == null) {
                item(key = "note") {
                    Note(stringResource(R.string.document_note), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** Find field with "2 / 5" in mono (TalkBack: "Match 2 of 5") and previous/next. */
@Composable
private fun FindBar(find: FindState, actions: TextPaneActions) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val count: String
    val spokenCount: String
    if (find.matches.isEmpty()) {
        count = stringResource(R.string.document_find_none)
        spokenCount = stringResource(R.string.document_find_none_spoken)
    } else {
        count = stringResource(R.string.document_find_count, find.current + 1, find.matches.size)
        spokenCount = stringResource(R.string.document_find_count_spoken, find.current + 1, find.matches.size)
    }
    LaunchedEffect(Unit) { if (find.query.isEmpty()) focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = find.query,
            onValueChange = actions.onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.document_find_hint), style = Type.caption) },
            leadingIcon = { Icon(painterResource(R.drawable.document_ic_search), null, tint = Paper.muted) },
            trailingIcon = if (find.query.isNotEmpty()) {
                {
                    Text(
                        count,
                        style = Type.figure,
                        color = Paper.ink,
                        modifier = Modifier.padding(end = 12.dp).semantics {
                            contentDescription = spokenCount
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
            } else null,
            shape = RoundedCornerShape(2.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Paper.ink,
                unfocusedBorderColor = Paper.line,
                cursorColor = Paper.accent,
                focusedContainerColor = Paper.sheet,
                unfocusedContainerColor = Paper.sheet,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); actions.onNext() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        InkIconButton(R.drawable.document_ic_arrow_up, stringResource(R.string.document_find_previous), actions.onPrevious, enabled = find.matches.isNotEmpty())
        InkIconButton(R.drawable.document_ic_arrow_down, stringResource(R.string.document_find_next), actions.onNext, enabled = find.matches.isNotEmpty())
        InkIconButton(R.drawable.document_ic_close, stringResource(R.string.document_find_close), actions.onCloseFind)
    }
}

/**
 * Small page frames; a red square marks pages with matches, the current one is outlined in ink. TalkBack hears the
 * page, whether it is the current one and whether it has matches.
 */
@Composable
private fun FrameStrip(pages: List<Page>, matchPages: Set<PageId>, current: Int, onOpen: (Int) -> Unit) {
    if (pages.size < 2) return
    val accent = Paper.accent
    val hasMatches = stringResource(R.string.document_has_matches)
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(pages, key = { _, p -> p.id.value }) { index, page ->
            val label = stringResource(R.string.document_page_of, index + 1, pages.size)
            val marked = page.id in matchPages
            Column(
                Modifier
                    .widthIn(min = 48.dp)
                    .clickable(onClickLabel = stringResource(R.string.document_open)) { onOpen(index) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        selected = index == current
                        if (marked) stateDescription = hasMatches
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(36.dp, 48.dp)) {
                    PageThumb(
                        page.thumbPath,
                        page.editsVersion,
                        targetPx = 96,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (index == current) Modifier.border(2.dp, Paper.ink) else Modifier.border(1.dp, Paper.rule)),
                    )
                    if (marked) {
                        Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(accent))
                    }
                }
                Text(
                    stringResource(R.string.document_frame_no, index + 1),
                    style = Type.frameNo,
                    color = if (index == current) Paper.accent else Paper.muted,
                    modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun PageTextSection(
    page: Page,
    index: Int,
    count: Int,
    text: PageText?,
    find: FindState,
    edit: TextEdit?,
    editing: Boolean,
    actions: TextPaneActions,
    onLayout: (TextLayoutResult) -> Unit,
) {
    val words = text?.text?.let(::wordCount) ?: 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // The kicker keeps its words whole: at large font sizes the count and Edit move under it.
        EndOrBelow(Modifier.fillMaxWidth().heightIn(min = PAGE_HEADER).padding(top = 12.dp), below = Alignment.End) {
            Kicker(stringResource(R.string.document_recognised_page, index + 1, count), Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (words > 0) Text(pluralStringResource(R.plurals.document_words, words, words), style = Type.figure, color = Paper.muted)
                if (text != null && !editing) {
                    val editPage = stringResource(R.string.document_edit_page, index + 1)
                    TextButton(
                        stringResource(R.string.document_edit),
                        { actions.onEdit(page.id) },
                        Modifier.padding(start = 8.dp).semantics { contentDescription = editPage },
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Paper.ink)
        Spacer(Modifier.height(10.dp))
        when {
            edit != null -> EditField(edit, actions)
            text != null && text.text.isNotBlank() -> {
                val styled = highlighted(text.text, find.matches.filter { it.pageId == page.id }.map { it.range }, find.currentMatch?.takeIf { it.pageId == page.id }?.range)
                SelectionContainer {
                    Text(styled, style = Type.reading, color = Paper.ink, onTextLayout = onLayout, modifier = Modifier.widthIn(max = 640.dp))
                }
            }
            page.textStatus == TextStatus.PENDING -> Column {
                Text(stringResource(R.string.document_reading_page), style = Type.caption, color = Paper.muted)
                Spacer(Modifier.height(8.dp))
                LoadBar(progress = null)
            }
            page.textStatus == TextStatus.FAILED -> StatusRow(stringResource(R.string.document_read_failed), stringResource(R.string.document_retry)) {
                actions.onRetry(page.id)
            }
            page.textStatus == TextStatus.SKIPPED -> StatusRow(stringResource(R.string.document_not_read), stringResource(R.string.document_read_text)) {
                actions.onRetry(page.id)
            }
            else -> Text(stringResource(R.string.document_no_text), style = Type.caption, color = Paper.muted)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatusRow(text: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.caption, color = Paper.muted, modifier = Modifier.weight(1f))
        InkTextButton(action, onAction)
    }
}

@Composable
private fun EditField(edit: TextEdit, actions: TextPaneActions) {
    val focus = remember { FocusRequester() }
    val onDraft by rememberUpdatedState(actions.onDraft)
    LaunchedEffect(edit.pageId) { focus.requestFocus() }
    OutlinedTextField(
        value = edit.draft,
        onValueChange = { onDraft(it) },
        textStyle = Type.reading,
        shape = RoundedCornerShape(2.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Paper.ink,
            unfocusedBorderColor = Paper.line,
            cursorColor = Paper.accent,
            focusedContainerColor = Paper.sheet,
            unfocusedContainerColor = Paper.sheet,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).focusRequester(focus),
    )
}

/** Matches in the selection colour and underlined (a cue that is not colour alone); the current one in reverse. */
@Composable
private fun highlighted(text: String, ranges: List<IntRange>, current: IntRange?): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(text)
    val match = SpanStyle(background = Paper.selection, textDecoration = TextDecoration.Underline)
    val active = SpanStyle(background = Paper.ink, color = Paper.onInk)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { r ->
            val start = r.first.coerceIn(0, text.length)
            val end = (r.last + 1).coerceIn(start, text.length)
            if (end > start) addStyle(if (r == current) active else match, start, end)
        }
    }
}

private val PAGE_HEADER = 44.dp
private val CONTEXT = 72.dp
