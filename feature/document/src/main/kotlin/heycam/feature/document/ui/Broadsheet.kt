package heycam.feature.document.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.dp
import heycam.core.designsystem.ActionBarItem
import heycam.core.designsystem.DayPaperColors
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.NightInkColors
import heycam.feature.document.R

/*
 * The module's names for the Broadsheet roles of :core:designsystem, so every screen draws with the house palette
 * (Heycam.colors) and the real Fraunces, Libre Franklin and IBM Plex Mono instances (Heycam.type).
 */
internal object Paper {
    val ink: Color @Composable @ReadOnlyComposable get() = Heycam.colors.ink
    val onInk: Color @Composable @ReadOnlyComposable get() = Heycam.colors.onInk
    val accent: Color @Composable @ReadOnlyComposable get() = Heycam.colors.accent
    val accentWash: Color @Composable @ReadOnlyComposable get() = Heycam.colors.accentWash
    val paper: Color @Composable @ReadOnlyComposable get() = Heycam.colors.paper
    val sheet: Color @Composable @ReadOnlyComposable get() = Heycam.colors.sheet
    val canvas: Color @Composable @ReadOnlyComposable get() = Heycam.colors.canvas
    val muted: Color @Composable @ReadOnlyComposable get() = Heycam.colors.muted
    val rule: Color @Composable @ReadOnlyComposable get() = Heycam.colors.rule
    val line: Color @Composable @ReadOnlyComposable get() = Heycam.colors.line
    /** Find matches other than the current one. */
    val selection: Color @Composable @ReadOnlyComposable get() = Heycam.colors.selection
    /** The other edition's accent: the action on an ink slip. */
    val otherAccent: Color @Composable @ReadOnlyComposable
        get() = if (Heycam.colors.isDark) DayPaperColors.accent else NightInkColors.accent
}

internal object Type {
    val title: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.title
    val reading: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.reading
    val caption: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.caption
    val body: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.body
    val label: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.label
    val button: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.button
    val kicker: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.kicker
    val figure: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.figure
    val frameNo: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.frameNo
}

/** The system bars and the camera cutout, which is a side inset in landscape. The keyboard is handled per screen. */
internal val ScreenInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** What a top bar pads for: the status bar above it and the cutout or navigation bar beside it. */
internal val TopBarInsets: WindowInsets
    @Composable get() = ScreenInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** What a bottom bar pads for: the navigation bar under it and the cutout or navigation bar beside it. */
internal val BottomBarInsets: WindowInsets
    @Composable get() = ScreenInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

/** 48 dp icon button with an ink 24 dp glyph. */
@Composable
internal fun InkIconButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Paper.ink,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = if (enabled) tint else Paper.rule, modifier = Modifier.size(24.dp))
    }
}

/** Accent text with an underline; 48 dp tall. */
@Composable
internal fun InkTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(2.dp), modifier = modifier.heightIn(min = 48.dp)) {
        Text(text, style = Type.button.copy(textDecoration = TextDecoration.Underline), color = if (enabled) Paper.accent else Paper.rule)
    }
}

/** A bottom-bar action: icon over a one-word label, sharing the row's width with its siblings. */
@Composable
internal fun RowScope.LabelledIcon(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    ActionBarItem(ImageVector.vectorResource(icon), label, onClick, modifier, enabled = enabled, destructive = destructive)
}

/** Back + optional kicker + serif single-line title + actions, ruled underneath. */
@Composable
internal fun ArticleTopBar(
    title: String,
    navigationIcon: Int,
    navigationLabel: String,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            InkIconButton(navigationIcon, navigationLabel, onNavigate)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (kicker != null) {
                    // One line, so the bar keeps its height however long the title is; TalkBack hears all of it.
                    Text(
                        kicker.toUpperCase(Locale.current),
                        style = Type.kicker,
                        color = Paper.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = kicker },
                    )
                }
                Text(title, style = Type.title, color = Paper.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            }
            actions()
        }
        HorizontalDivider(Modifier.align(Alignment.BottomStart), thickness = 1.dp, color = Paper.ink)
    }
}

/** 2 dp accent bar: indeterminate without [progress]. */
@Composable
internal fun LoadBar(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) {
        LinearProgressIndicator(modifier.fillMaxWidth().height(2.dp), color = Paper.accent, trackColor = Paper.rule, gapSize = 0.dp)
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier.fillMaxWidth().height(2.dp),
            color = Paper.accent,
            trackColor = Paper.rule,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** Ink slip; the action in the other theme's accent. */
@Composable
internal fun InkSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(data, shape = RectangleShape, containerColor = Paper.ink, contentColor = Paper.onInk, actionColor = Paper.otherAccent)
    }
}

/** Yes/no for anything that cannot be undone. */
@Composable
internal fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.document_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Paper.paper,
        title = { Text(title, style = Type.title, color = Paper.ink) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel, color = Paper.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(dismissLabel) }
        },
    )
}
