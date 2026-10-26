package heycam.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp

/**
 * The keyboard and D-pad focus ring: 2 dp accent, 2 dp outside the element. Put it before the clickable or focusable
 * modifier it should follow. Touch never moves focus to buttons, so the ring only shows for keyboard users.
 */
fun Modifier.focusRing(): Modifier = this then FocusRingElement

private object FocusRingElement : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode()
    override fun update(node: FocusRingNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "focusRing"
    }
    override fun hashCode() = "focusRing".hashCode()
    override fun equals(other: Any?) = other === this
}

private class FocusRingNode : Modifier.Node(), FocusEventModifierNode, DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focused != focusState.isFocused) {
            focused = focusState.isFocused
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val stroke = 2.dp.toPx()
        val inset = 2.dp.toPx() + stroke / 2
        drawRect(
            color = currentValueOf(LocalHeycamColors).accent,
            topLeft = Offset(-inset, -inset),
            size = Size(size.width + 2 * inset, size.height + 2 * inset),
            style = Stroke(stroke),
        )
    }
}
