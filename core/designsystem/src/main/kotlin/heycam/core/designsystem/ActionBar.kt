package heycam.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A bottom toolbar of labelled actions (page viewer, text view, multi-select) on a paper strip with a 1 dp ink rule
 * on top, above the navigation bar. Fill it with [ActionBarItem]s; a [PrimaryButton] may close the row.
 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.navigationBars,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Heycam.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.paper)
            .drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(size.width, 0f), HeycamSize.hair.toPx()) }
            .windowInsetsPadding(windowInsets)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(HeycamSize.touchSpacing / 2),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One action in a [BottomActionBar]: a 24 dp icon over a one-word label, at least 56 dp tall and sharing the row
 * width with its siblings. [destructive] prints it in accent (Delete); the label always says what it does.
 */
@Composable
fun RowScope.ActionBarItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val c = Heycam.colors
    val tint = when {
        !enabled -> c.line
        destructive -> c.accent
        else -> c.ink
    }
    Column(
        modifier = modifier
            .weight(1f)
            .focusRing()
            .heightIn(min = HeycamSize.buttonLarge)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(HeycamSize.icon))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = Heycam.type.label.copy(fontSize = 12.sp, lineHeight = 15.sp),
            color = if (enabled) tint else c.muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
