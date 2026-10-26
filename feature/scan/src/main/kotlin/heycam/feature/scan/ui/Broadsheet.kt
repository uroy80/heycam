package heycam.feature.scan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.dp
import heycam.core.designsystem.Heycam

/*
 * The module's names for the Broadsheet roles of :core:designsystem, so every screen draws with the house palette
 * (Heycam.colors) and the real Fraunces, Libre Franklin and IBM Plex Mono instances (Heycam.type).
 */
internal object Paper {
    val ink: Color @Composable @ReadOnlyComposable get() = Heycam.colors.ink
    val onInk: Color @Composable @ReadOnlyComposable get() = Heycam.colors.onInk
    val accent: Color @Composable @ReadOnlyComposable get() = Heycam.colors.accent
    val paper: Color @Composable @ReadOnlyComposable get() = Heycam.colors.paper
    val canvas: Color @Composable @ReadOnlyComposable get() = Heycam.colors.canvas
    val muted: Color @Composable @ReadOnlyComposable get() = Heycam.colors.muted
    val rule: Color @Composable @ReadOnlyComposable get() = Heycam.colors.rule
    val line: Color @Composable @ReadOnlyComposable get() = Heycam.colors.line
}

internal object Type {
    val title: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.title
    val body: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.body
    val button: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.button
    val kicker: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.kicker
    val modeLabel: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.modeLabel
    val figure: TextStyle @Composable @ReadOnlyComposable get() = Heycam.type.figure
}

/** The system bars and the camera cutout, which is a side inset in landscape. */
internal val ScreenInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** What a top bar pads for: the status bar above it and the cutout or navigation bar beside it. */
internal val TopBarInsets: WindowInsets
    @Composable get() = ScreenInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** What a bottom bar pads for: the navigation bar under it and the cutout or navigation bar beside it. */
internal val BottomBarInsets: WindowInsets
    @Composable get() = ScreenInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

@Composable
internal fun InkIconButton(icon: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = if (enabled) Paper.ink else Paper.rule, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun InkTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, shape = RoundedCornerShape(2.dp), modifier = modifier.heightIn(min = 48.dp)) {
        Text(text, style = Type.button.copy(textDecoration = TextDecoration.Underline), color = Paper.accent)
    }
}

/** Back + optional kicker + serif title + actions, ruled underneath. */
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
                    // One line, so the bar keeps its height; TalkBack hears the sentence-case source.
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
