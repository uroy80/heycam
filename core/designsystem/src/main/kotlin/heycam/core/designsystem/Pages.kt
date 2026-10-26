package heycam.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Width / height of a portrait A4 page, the default frame for a page whose size is not known yet. */
const val A4_PORTRAIT_RATIO = 1f / 1.4142f

/** Draws a [width] outline [gap] outside the element, like a printer's frame around the current page. */
internal fun Modifier.outsideOutline(show: Boolean, color: androidx.compose.ui.graphics.Color, width: androidx.compose.ui.unit.Dp = 2.dp, gap: androidx.compose.ui.unit.Dp = 2.dp) =
    drawWithContent {
        drawContent()
        if (show) {
            val w = width.toPx()
            val inset = gap.toPx() + w / 2
            drawRect(color, Offset(-inset, -inset), Size(size.width + 2 * inset, size.height + 2 * inset), style = Stroke(w))
        }
    }

/** A loading placeholder in checker tone, sized by the caller. */
@Composable
fun PageSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.background(Heycam.colors.checker))
}

/**
 * A page in a grid or strip: the page at its own [aspectRatio] on sheet stock with a hairline border and its number
 * in mono underneath. The [current] page gets a 2 dp ink frame and an accent number; [marked] pages (find matches)
 * carry a 7 dp accent square. TalkBack hears [contentDescription] (default "Page n") plus the match state; add click
 * handling through [modifier].
 */
@Composable
fun PageThumb(
    number: Int,
    modifier: Modifier = Modifier,
    aspectRatio: Float = A4_PORTRAIT_RATIO,
    current: Boolean = false,
    marked: Boolean = false,
    contentDescription: String = stringResource(R.string.hc_page_number, number),
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    val c = Heycam.colors
    val markedState = stringResource(R.string.hc_has_matches)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            this.contentDescription = contentDescription
            if (current) selected = true
            if (marked) stateDescription = markedState
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .outsideOutline(current, c.ink)
                .background(c.sheet)
                .border(HeycamSize.hair, c.rule)
                .clearAndSetSemantics {},
        ) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                if (content != null) content() else PageSkeleton(Modifier.fillMaxSize())
            }
            if (marked) Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(c.accent))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = number.toString().padStart(2, '0'),
            style = if (current) Heycam.type.frameNo.copy(fontWeight = FontWeight.Medium) else Heycam.type.frameNo,
            color = if (current) c.accent else c.muted,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The filter choices under a page: a live [preview] per filter (64 x 84 dp) with its name in caps underneath. The
 * chosen one gets a 2 dp ink frame, an accent name and a check. Radio semantics.
 */
@Composable
fun FilterStrip(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable BoxScope.(index: Int) -> Unit,
) {
    val c = Heycam.colors
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(horizontal = HeycamSize.gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                modifier = Modifier
                    .focusRing()
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) })
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(HeycamSize.filterThumb)
                        .outsideOutline(on, c.ink)
                        .background(c.sheet)
                        .border(HeycamSize.hair, c.rule)
                        .clipToBounds(),
                ) { preview(i) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (on) {
                        Icon(HeycamIcons.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(2.dp))
                    }
                    CapsText(label, Heycam.type.modeLabel.copy(fontSize = 11.sp, lineHeight = 13.sp), if (on) c.accent else c.ink2, maxLines = 1)
                }
            }
        }
    }
}
