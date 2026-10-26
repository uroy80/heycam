package heycam.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The square, printed toggle. The whole row is the touch target (at least 56 dp) and carries Role.Switch. */
@Composable
fun PrintedSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    val c = Heycam.colors
    val thumbX by animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = if (Heycam.reducedMotion) snap() else tween(HeycamMotion.BASE, easing = HeycamMotion.easing),
        label = "thumb",
    )
    Row(
        modifier = modifier
            .focusRing()
            .fillMaxWidth()
            .heightIn(min = HeycamSize.settingsRow)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = Heycam.type.label, color = if (enabled) c.ink else c.muted)
            if (hint != null) Text(hint, style = Heycam.type.hint, color = c.muted)
        }
        Box(
            Modifier
                .size(width = 44.dp, height = 26.dp)
                .background(if (checked) c.ink else c.sheet)
                .border(1.5.dp, if (enabled) c.ink else c.line),
        ) {
            Box(
                Modifier
                    .offset { IntOffset(thumbX.roundToPx(), 5.dp.roundToPx()) }
                    .size(16.dp)
                    .background(if (checked) c.onInk else c.muted),
            )
        }
    }
}

/**
 * A ruled table of choices; the chosen one is printed in reverse. 48 dp cells with radio semantics. [details] adds a
 * mono second line per option (for example the export size estimate); [icons] puts a glyph before each label (the
 * document's Pages | Text toggle); [spoken] replaces what TalkBack reads for each option ("Small, about 600 kB").
 * When a word of a label or a detail would have to break (large font sizes, narrow screens), the options stack into
 * one full-width row each instead.
 */
@Composable
fun SegmentedRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    details: List<String>? = null,
    icons: List<ImageVector>? = null,
    enabled: Boolean = true,
    spoken: List<String>? = null,
) {
    require(details == null || details.size == options.size) { "details must match options" }
    require(icons == null || icons.size == options.size) { "icons must match options" }
    require(spoken == null || spoken.size == options.size) { "spoken must match options" }
    val c = Heycam.colors
    val base = if (mono) Heycam.type.figureStrong.copy(fontSize = 13.sp) else Heycam.type.label.copy(fontSize = 14.sp)
    val strong = base.copy(fontWeight = FontWeight(650))
    val detailStyle = Heycam.type.frameNo
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // A cell is its share of the row less the hairlines, its padding and the icon.
        val cell = if (constraints.hasBoundedWidth) {
            val share = (maxWidth - HeycamSize.hair * (options.size - 1)) / options.size - SEGMENT_PADDING * 2
            share - if (icons != null) SEGMENT_ICON + SEGMENT_ICON_GAP else 0.dp
        } else {
            Dp.Infinity
        }
        val stacked = remember(options, details, strong, detailStyle, cell, density) {
            val limit = with(density) { cell.toPx() }
            fun tooWide(text: String, style: TextStyle) =
                measurer.measure(text, style, softWrap = false, maxLines = 1, density = density).size.width > limit
            cell != Dp.Infinity && (
                options.any { label -> label.split(' ').any { it.isNotEmpty() && tooWide(it, strong) } } ||
                    details.orEmpty().any { tooWide(it, detailStyle) }
                )
        }
        fun cellModifier(i: Int, on: Boolean): Modifier = Modifier
            .background(if (on) c.ink else c.sheet)
            .focusRing()
            .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(i) })
            .then(spoken?.get(i)?.let { said -> Modifier.semantics { contentDescription = said } } ?: Modifier)

        @Composable
        fun Label(i: Int, on: Boolean, ink: Color, align: TextAlign) {
            icons?.get(i)?.let {
                Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(SEGMENT_ICON))
                Spacer(Modifier.width(SEGMENT_ICON_GAP))
            }
            Text(options[i], style = if (on) strong else base, color = ink, textAlign = align, maxLines = 2)
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth().border(HeycamSize.hair, c.line).selectableGroup()) {
                options.indices.forEach { i ->
                    val on = i == selected
                    val ink = segmentInk(on, enabled)
                    if (i > 0) HorizontalDivider(thickness = HeycamSize.hair, color = c.line)
                    Row(
                        cellModifier(i, on)
                            .fillMaxWidth()
                            .heightIn(min = HeycamSize.segment)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { Label(i, on, ink, TextAlign.Start) }
                        details?.get(i)?.let {
                            Text(it, style = detailStyle, color = if (on) c.onInk else c.muted, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .border(HeycamSize.hair, c.line)
                    .selectableGroup(),
            ) {
                options.indices.forEach { i ->
                    val on = i == selected
                    val ink = segmentInk(on, enabled)
                    if (i > 0) VerticalDivider(thickness = HeycamSize.hair, color = c.line)
                    Column(
                        cellModifier(i, on)
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = HeycamSize.segment)
                            .padding(horizontal = SEGMENT_PADDING, vertical = 6.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Label(i, on, ink, TextAlign.Center) }
                        details?.get(i)?.let { Text(it, style = detailStyle, color = if (on) c.onInk else c.muted, maxLines = 1) }
                    }
                }
            }
        }
    }
}

private val SEGMENT_PADDING = 6.dp
private val SEGMENT_ICON = 18.dp
private val SEGMENT_ICON_GAP = 6.dp

@Composable
private fun segmentInk(on: Boolean, enabled: Boolean): Color = when {
    on -> Heycam.colors.onInk
    enabled -> Heycam.colors.ink
    else -> Heycam.colors.muted
}

/**
 * A text field on sheet stock. Focused: 2 dp ink border and an accent cursor. The placeholder is italic serif so a
 * hint never reads as a value. [error] prints the field in accent with a message (and is announced by TalkBack).
 * [label] sits above the box and is part of the field, so TalkBack reads it with the value.
 */
@Composable
fun HeycamTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    suffix: String? = null,
    error: String? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    textStyle: TextStyle? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource? = null,
) {
    val c = Heycam.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val style = textStyle ?: if (mono) Heycam.type.figureStrong.copy(fontSize = 15.sp, lineHeight = 20.sp) else Heycam.type.body
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = style.copy(color = if (enabled) c.ink else c.muted),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = source,
        modifier = modifier.semantics { if (error != null) error(error) },
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth()) {
                if (label != null) {
                    Text(label, style = Heycam.type.label, color = c.ink, modifier = Modifier.padding(bottom = 8.dp))
                }
                val borderWidth = if (focused || error != null) 2.dp else HeycamSize.hair
                val borderColor = when {
                    error != null -> c.accent
                    focused -> c.ink
                    else -> c.line
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = HeycamSize.textField)
                        .background(if (error != null) c.accentWash else c.sheet, BroadsheetShapes.extraSmall)
                        .border(borderWidth, borderColor, BroadsheetShapes.extraSmall)
                        .drawBehind {
                            if (error != null) {
                                val w = HeycamSize.ruleThick.toPx()
                                val x = if (layoutDirection == LayoutDirection.Ltr) w / 2 else size.width - w / 2
                                drawLine(c.accent, Offset(x, 0f), Offset(x, size.height), w)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = Heycam.type.caption, color = c.muted, maxLines = 1)
                        }
                        inner()
                    }
                    if (suffix != null) {
                        Text(suffix, style = Heycam.type.frameNo, color = c.muted, modifier = Modifier.padding(start = 8.dp))
                    }
                    if (trailing != null) {
                        Spacer(Modifier.width(8.dp))
                        trailing()
                    }
                }
                if (error != null) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HeycamIcons.Error, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(error, style = Heycam.type.hint, color = c.accent)
                    }
                }
            }
        },
    )
}

/**
 * The find bar for recognised text: a search field with "n / m" in mono, then previous and next. [current] is
 * 1-based; with no matches the counter reads 0 / 0 and the arrows are disabled.
 */
@Composable
fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    current: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Heycam.colors
    val counterDescription = if (total == 0) {
        stringResource(R.string.hc_find_none)
    } else {
        stringResource(R.string.hc_find_position, current, total)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HeycamTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.hc_find),
            leadingIcon = HeycamIcons.Search,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (total > 0) onNext() }),
            trailing = if (query.isEmpty()) {
                null
            } else {
                {
                    Text(
                        text = "${if (total == 0) 0 else current} / $total",
                        style = Heycam.type.figure,
                        color = if (total == 0) c.accent else c.muted,
                        modifier = Modifier.semantics {
                            contentDescription = counterDescription
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(HeycamIcons.Previous, stringResource(R.string.hc_find_previous), onPrevious, enabled = total > 0)
        IconButton(HeycamIcons.Next, stringResource(R.string.hc_find_next), onNext, enabled = total > 0)
    }
}
