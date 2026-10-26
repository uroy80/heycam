package heycam.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reordering pages by drag and by TalkBack custom actions, with the drag bar and the delete target. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp-mdpi")
class PageGridReorderTest {
    @get:Rule val compose = createComposeRule()

    private val pages = mutableStateListOf("a", "b", "c", "d", "e", "f")
    private val moves = mutableListOf<Pair<Int, Int>>()
    private val deleted = mutableListOf<Int>()
    private val opened = mutableListOf<Int>()
    private val askedPosition = mutableListOf<Int>()

    private fun show(withDelete: Boolean = true) = compose.setContent {
        HeycamTheme(ThemeMode.DayPaper) {
            val state = rememberPageReorderState()
            Column {
                PageGridReorder(
                    pages = pages,
                    key = { it },
                    onMove = { from, to ->
                        moves += from to to
                        pages.add(to, pages.removeAt(from))
                    },
                    onOpen = { opened += it },
                    onDelete = if (withDelete) ({ deleted += it }) else null,
                    onMoveToPosition = { askedPosition += it },
                    state = state,
                    modifier = Modifier.fillMaxWidth().height(600.dp).testTag("grid"),
                ) { }
                if (state.isDragging) PageReorderBar(state, Modifier.testTag("bar"))
            }
        }
    }

    private fun actionLabels(description: String): List<String> =
        compose.onNodeWithContentDescription(description).fetchSemanticsNode()
            .config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.map { it.label }

    @Test
    fun eachPageOffersTheMovesThatMakeSense() {
        show()
        assertThat(actionLabels("Page 1 of 6"))
            .containsExactly("Move later", "Move to end", "Move to position…", "Delete page").inOrder()
        assertThat(actionLabels("Page 3 of 6")).containsExactly(
            "Move earlier", "Move later", "Move to start", "Move to end", "Move to position…", "Delete page",
        ).inOrder()
        assertThat(actionLabels("Page 6 of 6"))
            .containsExactly("Move earlier", "Move to start", "Move to position…", "Delete page").inOrder()
    }

    @Test
    fun accessibilityActionsMoveDeleteAndAskForAPosition() {
        show()
        compose.onNodeWithContentDescription("Page 1 of 6").performCustomAccessibilityActionWithLabel("Move later")
        assertThat(moves).containsExactly(0 to 1)
        assertThat(pages).containsExactly("b", "a", "c", "d", "e", "f").inOrder()
        compose.onNodeWithContentDescription("Page 6 of 6").performCustomAccessibilityActionWithLabel("Move to start")
        assertThat(moves.last()).isEqualTo(5 to 0)
        compose.onNodeWithContentDescription("Page 2 of 6").performCustomAccessibilityActionWithLabel("Move to position…")
        assertThat(askedPosition).containsExactly(1)
        compose.onNodeWithContentDescription("Page 4 of 6").performCustomAccessibilityActionWithLabel("Delete page")
        assertThat(deleted).containsExactly(3)
    }

    @Test
    fun withoutDeleteThereIsNoDeleteAction() {
        show(withDelete = false)
        assertThat(actionLabels("Page 2 of 6")).doesNotContain("Delete page")
    }

    @Test
    fun tappingAPageOpensIt() {
        show()
        compose.onNodeWithContentDescription("Page 2 of 6").performClick()
        assertThat(opened).containsExactly(1)
    }

    @Test
    fun longPressAndDragMovesAPageAndShowsTheBar() {
        show()
        val grid = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot.topLeft
        fun centreOf(description: String): Offset =
            compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.center - grid

        val from = centreOf("Page 1 of 6")
        val to = centreOf("Page 3 of 6")
        compose.onNodeWithTag("grid").performTouchInput {
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(from + Offset(20f, 0f))
            moveTo((from + to) / 2f)
            moveTo(to)
        }
        compose.onNodeWithContentDescription("Moving page 1 to position 3").assertIsDisplayed()
        compose.onNodeWithTag("grid").performTouchInput { up() }
        assertThat(moves).containsExactly(0 to 2)
        assertThat(pages).containsExactly("b", "c", "a", "d", "e", "f").inOrder()
        compose.onNodeWithTag("bar").assertDoesNotExist()
    }

    @Test
    fun dropOnTheDeleteTargetDeletesThePage() {
        show()
        val grid = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot.topLeft
        val from = compose.onNodeWithContentDescription("Page 2 of 6").fetchSemanticsNode().boundsInRoot.center - grid
        compose.onNodeWithTag("grid").performTouchInput {
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(from + Offset(0f, 20f))
        }
        val target = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val deleteCentre = Offset(target.right - 60f, target.center.y) - grid
        compose.onNodeWithTag("grid").performTouchInput {
            moveTo(deleteCentre)
        }
        compose.onNodeWithContentDescription("Drop to delete page 2").assertIsDisplayed()
        compose.onNodeWithTag("grid").performTouchInput { up() }
        assertThat(deleted).containsExactly(1)
        assertThat(moves).isEmpty()
    }
}
