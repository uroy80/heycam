package heycam.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * An overflow or sort menu printed on the page's paper with a 1 dp ink edge and no shadow, like [HeycamDialog]'s
 * card. A shadow is all that edges a Material menu, and it vanishes on Night ink's near-black. Fill it with
 * Material DropdownMenuItems.
 */
@Composable
fun HeycamMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Heycam.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(2.dp),
        containerColor = c.paper,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(HeycamSize.hair, c.ink),
        content = content,
    )
}
