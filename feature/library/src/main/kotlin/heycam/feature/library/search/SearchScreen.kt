package heycam.feature.library.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import heycam.core.designsystem.SectionHeader
import heycam.core.model.DocumentId
import heycam.core.model.SearchHit
import heycam.feature.library.R
import heycam.feature.library.ui.InkIconButton
import heycam.feature.library.ui.LibraryFormat
import heycam.feature.library.ui.LoadBar
import heycam.feature.library.ui.Paper
import heycam.feature.library.ui.ScreenInsets
import heycam.feature.library.ui.Thumb
import heycam.feature.library.ui.TopBarInsets
import heycam.feature.library.ui.Type
import java.time.LocalDate

@Composable
internal fun SearchDestination(
    onBack: () -> Unit,
    onOpen: (documentId: DocumentId, pageIndex: Int?, highlight: String?) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    SearchScreen(query, results, LocalDate.now(), viewModel::onQueryChange, onBack, onOpen)
}

/** Search (S3): one field, then "Titles" and "In the text". */
@Composable
internal fun SearchScreen(
    query: String,
    results: SearchResults,
    today: LocalDate,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpen: (documentId: DocumentId, pageIndex: Int?, highlight: String?) -> Unit,
) {
    val context = LocalContext.current
    val format = remember(context) { LibraryFormat(context) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val column = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)
    val sectionPadding = Modifier.padding(horizontal = 16.dp)
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        containerColor = Paper.paper,
        contentWindowInsets = ScreenInsets,
        topBar = {
            Column(Modifier.background(Paper.paper).windowInsetsPadding(TopBarInsets)) {
                Row(column.heightIn(min = 64.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    InkIconButton(R.drawable.library_ic_arrow_back, stringResource(R.string.library_back), onBack)
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.library_search_hint), style = Type.body) },
                        leadingIcon = { Icon(painterResource(R.drawable.library_ic_search), null, tint = Paper.muted) },
                        trailingIcon = if (query.isNotEmpty()) {
                            { InkIconButton(R.drawable.library_ic_close, stringResource(R.string.library_clear), { onQueryChange("") }) }
                        } else null,
                        shape = RoundedCornerShape(2.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Paper.ink,
                            unfocusedBorderColor = Paper.line,
                            cursorColor = Paper.accent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = Paper.ink)
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().imePadding()) {
            when (results) {
                SearchResults.Idle -> Unit
                is SearchResults.Searching -> LoadBar(progress = null, modifier = column)
                SearchResults.Failed -> Message(stringResource(R.string.library_search_failed), column)
                is SearchResults.Done -> if (results.isEmpty) {
                    Message(stringResource(R.string.library_no_matches), column)
                } else {
                    LazyColumn(column, contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (results.titles.isNotEmpty()) {
                            item(key = "titles") { SectionHeader(stringResource(R.string.library_titles), sectionPadding, count = results.titles.size) }
                            items(results.titles, key = { "t${it.document.id.value}" }) { hit ->
                                TitleHitRow(hit, format, today) { onOpen(hit.document.id, null, null) }
                            }
                        }
                        if (results.inText.isNotEmpty()) {
                            item(key = "text") { SectionHeader(stringResource(R.string.library_in_the_text), sectionPadding, count = results.inText.size) }
                            items(results.inText, key = { "x${it.pageId.value}" }) { hit ->
                                TextHitRow(hit) { onOpen(hit.document.id, hit.pageIndex, results.query) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Text(
        text,
        style = Type.body,
        color = Paper.muted,
        modifier = modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Matches printed in reverse (ink block, paper type). */
@Composable
private fun highlighted(text: String, matches: List<IntRange>): AnnotatedString {
    val style = SpanStyle(background = Paper.ink, color = Paper.onInk)
    return buildAnnotatedString {
        append(text)
        matches.forEach { r ->
            val start = r.first.coerceIn(0, text.length)
            val end = (r.last + 1).coerceIn(start, text.length)
            if (end > start) addStyle(style, start, end)
        }
    }
}

@Composable
private fun TitleHitRow(hit: SearchHit.TitleHit, format: LibraryFormat, today: LocalDate, onClick: () -> Unit) {
    val doc = hit.document
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(onClickLabel = stringResource(R.string.library_open), onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = doc.title + ", " + format.pages(doc.pageCount) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(doc.coverThumbPath, doc.updatedAt, 44.dp, 60.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(highlighted(doc.title, listOf(hit.match)), style = Type.rowTitle, color = Paper.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(format.stamp(doc.updatedAt, today) + " · " + format.pages(doc.pageCount), style = Type.figure, color = Paper.muted)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Paper.rule)
    }
}

@Composable
private fun TextHitRow(hit: SearchHit.TextHit, onClick: () -> Unit) {
    val page = stringResource(R.string.library_page_n, hit.pageIndex + 1)
    Column {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(onClickLabel = stringResource(R.string.library_open), onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = "${hit.document.title}, $page: ${hit.snippet.text}" }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.document.title,
                    style = Type.label,
                    color = Paper.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(page, style = Type.figure, color = Paper.muted)
            }
            Text(
                highlighted(hit.snippet.text, hit.snippet.matches),
                style = Type.body,
                color = Paper.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(thickness = 1.dp, color = Paper.rule)
    }
}
