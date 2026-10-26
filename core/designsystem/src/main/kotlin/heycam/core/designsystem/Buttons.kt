package heycam.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ButtonShape = BroadsheetShapes.extraSmall
private val ButtonPadding = PaddingValues(horizontal = 18.dp)

/** Ink block with paper type; turns editorial red while pressed (heyp's primary button). */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Heycam.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) c.accent else c.ink,
            contentColor = c.onInk,
            disabledContainerColor = c.rule,
            disabledContentColor = c.muted,
        ),
        contentPadding = ButtonPadding,
        modifier = modifier.focusRing().heightIn(min = if (large) HeycamSize.buttonLarge else HeycamSize.button),
        content = content,
    )
}

/** [PrimaryButton] with a label and an optional leading icon. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    large: Boolean = false,
    enabled: Boolean = true,
) {
    PrimaryButton(onClick, modifier, large, enabled) { ButtonLabel(text, icon, large) }
}

/** 1 dp ink outline on a transparent fill. */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Heycam.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        border = BorderStroke(HeycamSize.hair, if (enabled) c.ink else c.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink, disabledContentColor = c.muted),
        contentPadding = ButtonPadding,
        modifier = modifier.focusRing().heightIn(min = if (large) HeycamSize.buttonLarge else HeycamSize.button),
        content = content,
    )
}

/** [SecondaryButton] with a label and an optional leading icon. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    large: Boolean = false,
    enabled: Boolean = true,
) {
    SecondaryButton(onClick, modifier, large, enabled) { ButtonLabel(text, icon, large) }
}

@Composable
private fun ButtonLabel(text: String, icon: ImageVector?, large: Boolean) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
    }
    val style = Heycam.type.button
    Text(text, style = if (large) style.copy(fontSize = 16.sp) else style, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** An underlined accent link-button (theme.json TextButton): 48 dp tall, underline 3 dp under the baseline. */
@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Heycam.colors.accent,
) {
    val tint = if (enabled) color else Heycam.colors.muted
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(
        modifier = modifier
            .focusRing()
            .heightIn(min = HeycamSize.touch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = Heycam.type.button.copy(fontSize = 14.sp),
            color = tint,
            onTextLayout = { layout = it },
            modifier = Modifier.drawBehind {
                val l = layout ?: return@drawBehind
                val stroke = HeycamSize.hair.toPx()
                for (line in 0 until l.lineCount) {
                    val y = l.getLineBaseline(line) + 3.dp.toPx()
                    drawLine(tint, Offset(l.getLineLeft(line), y), Offset(l.getLineRight(line), y), stroke)
                }
            },
        )
    }
}

/** A 48 dp square icon button; the press shows as a square ink overlay. */
@Composable
fun IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Heycam.colors.ink,
) {
    Box(
        modifier = modifier
            .focusRing()
            .size(HeycamSize.touch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else Heycam.colors.line, modifier = Modifier.size(HeycamSize.icon))
    }
}

/**
 * The "?" that replaces hint text under a control (COPY RULE): a muted info button that shows [text] in a small
 * dialog titled [title]. TalkBack hears "About <title>".
 */
@Composable
fun InfoButton(title: String, text: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(
        icon = HeycamIcons.Info,
        contentDescription = stringResource(R.string.hc_about, title),
        onClick = { open = true },
        modifier = modifier,
        tint = Heycam.colors.muted,
    )
    if (open) {
        HeycamDialog(
            title = title,
            text = text,
            confirmLabel = stringResource(R.string.hc_ok),
            onConfirm = { open = false },
            onDismissRequest = { open = false },
            dismissLabel = null,
        )
    }
}

/** A small mono choice (page sizes, scripts): 32 dp tall inside a 48 dp touch target; chosen = printed in reverse. */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Heycam.colors
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .focusRing()
            .heightIn(min = HeycamSize.chip)
            .background(if (selected) c.ink else Color.Transparent, ButtonShape)
            .border(HeycamSize.hair, if (selected) c.ink else c.line, ButtonShape)
            .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Heycam.type.figureStrong.copy(fontSize = 12.sp),
            color = when {
                !enabled -> c.muted
                selected -> c.onInk
                else -> c.ink
            },
            maxLines = 1,
        )
    }
}

/**
 * Home's bottom bar: a square Import button and an ink Scan block filling the rest, on a paper strip with a 1 dp ink
 * rule on top, above the navigation bar.
 */
@Composable
fun ScanBar(
    onScan: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.navigationBars,
) {
    val c = Heycam.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.paper)
            .drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(size.width, 0f), HeycamSize.hair.toPx()) }
            .windowInsetsPadding(windowInsets)
            .padding(horizontal = HeycamSize.gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .focusRing()
                .size(HeycamSize.scanBar)
                .border(HeycamSize.hair, c.ink, RectangleShape)
                .clickable(role = Role.Button, onClick = onImport),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HeycamIcons.Gallery, stringResource(R.string.hc_import_photos), tint = c.ink, modifier = Modifier.size(HeycamSize.icon))
        }
        PrimaryButton(
            text = stringResource(R.string.hc_scan),
            onClick = onScan,
            icon = HeycamIcons.Camera,
            large = true,
            modifier = Modifier.weight(1f),
        )
    }
}
