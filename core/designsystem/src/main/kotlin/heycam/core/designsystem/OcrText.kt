package heycam.core.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp

/** About 64 characters of reading text: a comfortable measure on tablets; phones are narrower anyway. */
private val ReadingMeasure = 560.dp

/**
 * Recognised text in serif on paper, selectable. [matches] (character ranges) are marked in the selection colour and
 * the [currentMatch] (an index into [matches]) is printed in reverse and scrolled into view. The header shows an
 * optional [kicker] ("Recognised text · page 1 of 8") and [figure] ("412 words"); the honest [note] closes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OcrText(
    text: String,
    modifier: Modifier = Modifier,
    matches: List<IntRange> = emptyList(),
    currentMatch: Int? = null,
    kicker: String? = null,
    figure: String? = null,
    note: String? = stringResource(R.string.hc_ocr_note),
) {
    val c = Heycam.colors
    val requester = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = remember(text, matches, currentMatch, c) {
        buildAnnotatedString {
            append(text)
            matches.forEachIndexed { i, range ->
                val start = range.first.coerceIn(0, text.length)
                val end = (range.last + 1).coerceIn(start, text.length)
                val style = if (i == currentMatch) SpanStyle(color = c.paper, background = c.ink) else SpanStyle(background = c.selection)
                addStyle(style, start, end)
            }
        }
    }
    val target = currentMatch?.let { matches.getOrNull(it) }
    LaunchedEffect(target, layout) {
        val l = layout ?: return@LaunchedEffect
        val range = target ?: return@LaunchedEffect
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) requester.bringIntoView(l.getPathForRange(start, end).getBounds())
    }
    Column(modifier.widthIn(max = ReadingMeasure).fillMaxWidth()) {
        if (kicker != null || figure != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (kicker != null) Kicker(kicker, Modifier.weight(1f))
                if (figure != null) Text(figure, style = Heycam.type.figure, color = c.muted)
            }
            HorizontalDivider(thickness = HeycamSize.hair, color = c.ink)
            Spacer(Modifier.height(14.dp))
        }
        SelectionContainer {
            Text(
                text = annotated,
                style = Heycam.type.reading,
                color = c.ink,
                onTextLayout = { layout = it },
                modifier = Modifier.bringIntoViewRequester(requester),
            )
        }
        if (note != null) {
            Spacer(Modifier.height(18.dp))
            Note(note)
        }
    }
}
