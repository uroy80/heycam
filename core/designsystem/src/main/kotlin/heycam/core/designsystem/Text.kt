package heycam.core.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Upper-cases for print; TalkBack still gets the sentence-case [text]. */
@Composable
internal fun CapsText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text.toUpperCase(Locale.current),
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { contentDescription = text },
    )
}

/** Red letter-spaced caps above a headline. Past [maxLines] it ends in an ellipsis; TalkBack still hears it all. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = Heycam.colors.accent, maxLines: Int = Int.MAX_VALUE) {
    CapsText(text, Heycam.type.kicker, color, modifier, maxLines)
}

/**
 * A folder section or settings group head: ink caps, a hairline to the end edge, an optional count in mono. A folder
 * section opens with a 2 dp ink rule. With [onClick] the whole row opens the folder and ends with a chevron;
 * [onClickLabel] is what TalkBack says the tap does ("Open folder").
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    opensWithRule: Boolean = true,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Heycam.colors
    val action = if (onClick != null) {
        Modifier.focusRing().clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (onClick != null) HeycamSize.touch else 40.dp)
            .drawBehind {
                if (opensWithRule) {
                    val w = HeycamSize.ruleMedium.toPx()
                    drawLine(c.ink, Offset(0f, w / 2), Offset(size.width, w / 2), strokeWidth = w)
                }
            }
            .semantics(mergeDescendants = true) { heading() }
            .then(action),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The name takes what it needs (two lines at most) and the hairline runs on to the count or the end edge.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            CapsText(text, Heycam.type.sectionHead, c.ink, maxLines = 2)
            HorizontalDivider(Modifier.weight(1f).padding(horizontal = 10.dp), thickness = HeycamSize.hair, color = c.rule)
        }
        if (count != null) Text("$count", style = Heycam.type.figure, color = c.muted)
        if (onClick != null) {
            Icon(HeycamIcons.ChevronEnd, contentDescription = null, tint = c.ink, modifier = Modifier.padding(start = 8.dp).size(20.dp))
        }
    }
}

/** heycam :) — the wordmark. It is a logo, so it stops growing at 1.3x font scale; everything else reflows. */
@Composable
fun Nameplate(modifier: Modifier = Modifier) {
    val d = LocalDensity.current
    val description = stringResource(R.string.hc_nameplate)
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.3f))) {
        Text(
            text = "heycam :)",
            style = Heycam.type.nameplate,
            color = Heycam.colors.ink,
            maxLines = 1,
            modifier = modifier.semantics { contentDescription = description },
        )
    }
}

/** A newspaper tag ("TEXT", "LOCKED"): caps on an ink block, or an ink outline for a secondary tag such as a folder. */
@Composable
fun Flag(text: String, modifier: Modifier = Modifier, outlined: Boolean = false) {
    val c = Heycam.colors
    Box(
        modifier
            .then(if (outlined) Modifier.border(HeycamSize.hair, c.ink) else Modifier.background(c.ink))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        CapsText(text, Heycam.type.kicker.copy(fontSize = 10.sp, lineHeight = 12.sp), if (outlined) c.ink else c.onInk, maxLines = 1)
    }
}
