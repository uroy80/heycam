package heycam.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/* ───────────────────────────── snackbar ───────────────────────────── */

/**
 * What [HeycamSnackbarHost] shows. It stays up for [timeoutMillis] (stretched to the user's accessibility timeout);
 * the default is 6 s with an action (Undo) and 4 s without. [isError] adds an accent bar at the start edge.
 */
@Immutable
data class HeycamSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    val isError: Boolean = false,
    val timeoutMillis: Long = if (actionLabel != null) 6_000L else 4_000L,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration get() = SnackbarDuration.Indefinite
}

/** Shows a Broadsheet snackbar and suspends until it is dismissed or its action is pressed. */
suspend fun SnackbarHostState.showHeycamSnackbar(
    message: String,
    actionLabel: String? = null,
    isError: Boolean = false,
): SnackbarResult = showSnackbar(HeycamSnackbarVisuals(message, actionLabel, isError))

/** Snackbars printed in reverse (ink slip, paper type). Place it above the scan bar or bottom toolbar. */
@Composable
fun HeycamSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val a11y = LocalAccessibilityManager.current
    val current = hostState.currentSnackbarData
    LaunchedEffect(current) {
        val data = current ?: return@LaunchedEffect
        val visuals = data.visuals as? HeycamSnackbarVisuals ?: return@LaunchedEffect
        val timeout = a11y?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = visuals.timeoutMillis,
            containsIcons = false,
            containsText = true,
            containsControls = visuals.actionLabel != null,
        ) ?: visuals.timeoutMillis
        delay(timeout)
        data.dismiss()
    }
    SnackbarHost(hostState, modifier) { HeycamSnackbar(it) }
}

@Composable
internal fun HeycamSnackbar(data: SnackbarData) {
    val c = Heycam.colors
    val visuals = data.visuals
    val isError = (visuals as? HeycamSnackbarVisuals)?.isError == true
    // The action uses the other edition's red, which reads on ink in both themes.
    val inverseAccent = if (c.isDark) DayPaperColors.accent else NightInkColors.accent
    Row(
        Modifier
            .padding(horizontal = HeycamSize.gutter, vertical = 8.dp)
            .widthIn(max = HeycamSize.maxContentWidth)
            .fillMaxWidth()
            .heightIn(min = HeycamSize.buttonLarge)
            .background(c.ink)
            .drawBehind {
                if (isError) {
                    val w = 4.dp.toPx()
                    val x = if (layoutDirection == LayoutDirection.Ltr) w / 2 else size.width - w / 2
                    drawLine(inverseAccent, Offset(x, 0f), Offset(x, size.height), w)
                }
            }
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(visuals.message, style = Heycam.type.body, color = c.onInk, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        val action = visuals.actionLabel
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(action, onClick = { data.performAction() }, color = inverseAccent)
        }
    }
}

/* ───────────────────────────── dialog and sheets ───────────────────────────── */

/**
 * A square dialog with a 3 dp ink rule on top: serif title, optional one-line [text] or [content], then a dismiss
 * TextButton and a confirm PrimaryButton. The dismiss button calls [onDismiss] when it is an answer of its own
 * ("Export without text" beside "Wait"); otherwise it does what Back and a tap outside do, [onDismissRequest]. The
 * buttons wrap onto a second line when they do not fit side by side.
 */
@Composable
fun HeycamDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = stringResource(R.string.hc_cancel),
    text: String? = null,
    onDismiss: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        DialogCard(title, confirmLabel, onConfirm, onDismiss ?: onDismissRequest, modifier, dismissLabel, text, content)
    }
}

/** The printed card inside [HeycamDialog], separate so the design catalog can show it in place. */
@Composable
internal fun DialogCard(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    text: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Heycam.colors
    Column(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .background(c.paper)
            .border(HeycamSize.hair, c.ink)
            .drawBehind { topRule(c.ink) }
            .semantics { paneTitle = title }
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
    ) {
        Text(title, style = Heycam.type.title, color = c.ink, modifier = Modifier.semantics { heading() })
        if (text != null) {
            Spacer(Modifier.height(10.dp))
            Text(text, style = Heycam.type.body, color = c.ink2)
        }
        if (content != null) {
            Spacer(Modifier.height(12.dp))
            content()
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (dismissLabel != null) TextButton(dismissLabel, onDismiss)
            PrimaryButton(confirmLabel, onConfirm)
        }
    }
}

private fun DrawScope.topRule(color: Color) {
    val w = HeycamSize.ruleThick.toPx()
    drawLine(color, Offset(0f, w / 2), Offset(size.width, w / 2), w)
}

/**
 * The inside of a sheet: square paper with a 3 dp ink rule on top, a 32 x 4 dp ink handle, an optional kicker and a
 * serif title. Use it in [HeycamBottomSheet] or in a dialog destination anchored to the bottom.
 */
@Composable
fun SheetFrame(
    title: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    showHandle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Heycam.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.paper)
            .drawBehind { topRule(c.ink) }
            .semantics { paneTitle = title },
    ) {
        if (showHandle) SheetHandle(Modifier.align(Alignment.CenterHorizontally))
        Column(Modifier.padding(start = HeycamSize.gutter, end = HeycamSize.gutter, top = if (showHandle) 8.dp else 20.dp, bottom = HeycamSize.gutter)) {
            if (kicker != null) {
                Kicker(kicker)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                title,
                style = Heycam.type.headline.copy(fontSize = 24.sp, lineHeight = 28.sp),
                color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 8.dp, bottom = 8.dp)
            .size(32.dp, 4.dp)
            .background(Heycam.colors.ink)
            .clearAndSetSemantics {},
    )
}

/** A modal bottom sheet in Broadsheet dress around a [SheetFrame]; back gestures and the scrim dismiss it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeycamBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Heycam.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = c.paper,
        contentColor = c.ink,
        scrimColor = c.scrim,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        SheetFrame(title = title, kicker = kicker, content = content)
    }
}

/* ───────────────────────────── progress and empty states ───────────────────────────── */

/**
 * A 2 dp accent progress line. With [progress] (0..1) it fills; without, a 35 % bar slides every 1.1 s (a still 60 %
 * bar when animations are off).
 */
@Composable
fun LoadBar(modifier: Modifier = Modifier, progress: Float? = null, contentDescription: String? = null) {
    val c = Heycam.colors
    val reduced = Heycam.reducedMotion
    val sliding = if (progress == null && !reduced) {
        val transition = rememberInfiniteTransition(label = "loadbar")
        val f by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "slide",
        )
        f
    } else {
        null
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = if (progress == null) {
                    ProgressBarRangeInfo.Indeterminate
                } else {
                    ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                }
            },
    ) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        fun bar(from: Float, to: Float) {
            val start = from.coerceIn(0f, 1f) * size.width
            val end = to.coerceIn(0f, 1f) * size.width
            if (end <= start) return
            val left = if (rtl) size.width - end else start
            drawRect(c.accent, Offset(left, 0f), Size(end - start, size.height))
        }
        when {
            progress != null -> {
                drawRect(c.rule)
                bar(0f, progress)
            }
            sliding != null -> {
                // Start mid-track so the first frame already shows the bar; the wrap at 1 is seamless (bar off-track).
                val phase = (sliding + 0.5f) % 1f
                bar(-0.35f + 1.35f * phase, 1.35f * phase)
            }
            else -> bar(0f, 0.6f)
        }
    }
}

/**
 * One short status line on sheet stock: the scanner or text models downloading, text being read, or an error with
 * a way out. [busy] adds the sliding [LoadBar]; a [progress] (0..1) fills it and prints the percentage in mono. An
 * error gets the accent wash, an error icon and a 3 dp accent bar at the start edge. Changes are announced politely.
 */
@Composable
fun StatusBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    busy: Boolean = false,
    progress: Float? = null,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    require((actionLabel == null) == (onAction == null)) { "actionLabel and onAction go together" }
    val c = Heycam.colors
    val glyph = if (isError) HeycamIcons.Error else icon
    Column(
        modifier
            .fillMaxWidth()
            .background(if (isError) c.accentWash else c.sheet)
            .border(HeycamSize.hair, if (isError) c.accent else c.rule)
            .drawBehind {
                if (isError) {
                    val w = HeycamSize.ruleThick.toPx()
                    val x = if (layoutDirection == LayoutDirection.Ltr) w / 2 else size.width - w / 2
                    drawLine(c.accent, Offset(x, 0f), Offset(x, size.height), w)
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = HeycamSize.touch)
                .padding(start = 14.dp, end = if (actionLabel != null) 8.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) {
                Icon(glyph, contentDescription = null, tint = if (isError) c.accent else c.ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = Heycam.type.label,
                color = c.ink,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (progress != null && actionLabel == null) {
                val percent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
                Text(
                    text = "$percent%",
                    style = Heycam.type.figureStrong,
                    color = c.muted,
                    modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics {},
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(actionLabel, onAction, color = if (isError) c.accent else c.ink)
            }
        }
        if (busy || progress != null) LoadBar(progress = progress, contentDescription = text)
    }
}

/** An empty "picture box": a page with ink lines inside red printer's crop marks. Decorative. */
@Composable
fun EmptyPicture(modifier: Modifier = Modifier) {
    val c = Heycam.colors
    Canvas(modifier.size(width = 128.dp, height = 132.dp).clearAndSetSemantics {}) {
        val pageW = 84.dp.toPx()
        val pageH = 118.dp.toPx()
        val left = (size.width - pageW) / 2
        val top = (size.height - pageH) / 2
        drawRect(c.sheet, Offset(left, top), Size(pageW, pageH))
        drawRect(c.line, Offset(left, top), Size(pageW, pageH), style = Stroke(HeycamSize.hair.toPx()))
        val pad = 12.dp.toPx()
        drawRect(c.ink, Offset(left + pad, top + pad), Size(pageW - 2 * pad, 7.dp.toPx()))
        var y = top + pad + 18.dp.toPx()
        while (y < top + pageH - pad) {
            drawLine(c.rule, Offset(left + pad, y), Offset(left + pageW - pad, y), HeycamSize.hair.toPx())
            y += 7.dp.toPx()
        }
        cropMarks(c.accent, Offset(left - 14.dp.toPx(), top - 6.dp.toPx()), Size(pageW + 28.dp.toPx(), pageH + 12.dp.toPx()))
    }
}

/** Printer's L-shaped crop marks at the four corners of a rectangle: heycam's viewfinder motif (and its icon). */
internal fun DrawScope.cropMarks(color: Color, topLeft: Offset, size: Size, arm: Float = 22.dp.toPx(), stroke: Float = 4.dp.toPx()) {
    val l = topLeft.x
    val t = topLeft.y
    val r = l + size.width
    val b = t + size.height
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Square)
        drawLine(color, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Square)
    }
    corner(l, t, 1f, 1f)
    corner(r, t, -1f, 1f)
    corner(l, b, 1f, -1f)
    corner(r, b, -1f, -1f)
}

/**
 * A front page with nothing on it yet: the picture box, an accent kicker between ink rules, a display headline, an
 * optional lede, then [actions] (usually a large PrimaryButton and a SecondaryButton) and a [footer] under a hairline.
 */
@Composable
fun EmptyState(
    kicker: String,
    headline: String,
    modifier: Modifier = Modifier,
    lede: String? = null,
    picture: Boolean = true,
    actions: @Composable ColumnScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Heycam.colors
    Column(
        modifier
            .widthIn(max = HeycamSize.maxContentWidth)
            .fillMaxWidth()
            .padding(horizontal = HeycamSize.gutter, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (picture) {
            EmptyPicture()
            Spacer(Modifier.height(28.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), thickness = HeycamSize.hair, color = c.ink)
            Kicker(kicker, Modifier.padding(horizontal = 12.dp))
            HorizontalDivider(Modifier.weight(1f), thickness = HeycamSize.hair, color = c.ink)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            headline,
            style = Heycam.type.display.copy(fontSize = 36.sp, lineHeight = 39.sp),
            color = c.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (lede != null) {
            Spacer(Modifier.height(12.dp))
            Text(lede, style = Heycam.type.lede, color = c.ink2, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = actions)
        if (footer != null) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(thickness = HeycamSize.hair, color = c.rule)
            Spacer(Modifier.height(14.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}
