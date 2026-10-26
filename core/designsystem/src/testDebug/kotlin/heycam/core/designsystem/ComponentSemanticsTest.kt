package heycam.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Semantics of the shared components: what TalkBack hears, roles, states, custom actions and touch targets. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ComponentSemanticsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent { HeycamTheme(ThemeMode.DayPaper, content) }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun kickerPrintsCapsButReadsSentenceCase() {
        show { Kicker("Nothing scanned yet") }
        compose.onNodeWithText("NOTHING SCANNED YET").assertIsDisplayed()
        compose.onNodeWithContentDescription("Nothing scanned yet").assertIsDisplayed()
    }

    @Test
    fun sectionHeaderIsAHeadingAndOpensItsFolder() {
        var opened = false
        show { SectionHeader("Receipts", count = 6, onClick = { opened = true }, modifier = Modifier.testTag("head")) }
        compose.onNodeWithTag("head").assert(isHeading).assertHeightIsAtLeast(48.dp).performClick()
        assertThat(opened).isTrue()
    }

    @Test
    fun nameplateReadsHeycamAndStopsGrowingAtOnePointThreeTimes() {
        show {
            Row {
                ScaledFont(1.3f) { Nameplate(Modifier.testTag("at1.3")) }
                ScaledFont(2f) { Nameplate(Modifier.testTag("at2")) }
            }
        }
        compose.onNodeWithTag("at2").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("heycam")))
        val capped = compose.onNodeWithTag("at1.3").getUnclippedBoundsInRoot()
        val large = compose.onNodeWithTag("at2").getUnclippedBoundsInRoot()
        assertThat((large.bottom - large.top).value).isEqualTo((capped.bottom - capped.top).value)
    }

    @Test
    fun buttonsAndChipsKeepFortyEightDpTargets() {
        show {
            Column {
                IconButton(HeycamIcons.Search, "Search", {}, Modifier.testTag("icon"))
                Chip("A4", selected = true, onClick = {}, modifier = Modifier.testTag("chip"))
                TextButton("Open", {}, Modifier.testTag("text"))
                PrimaryButton("Scan", {}, Modifier.testTag("primary"))
            }
        }
        listOf("icon", "chip", "text", "primary").forEach { tag ->
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val touch = with(node.layoutInfo.density) { node.touchBoundsInRoot.let { DpSize(it.width.toDp(), it.height.toDp()) } }
            assertWithMessage("$tag touch height").that(touch.height.value).isAtLeast(47.5f)
            assertWithMessage("$tag touch width").that(touch.width.value).isAtLeast(47.5f)
        }
        compose.onNodeWithTag("chip").assertIsSelected()
        compose.onNodeWithContentDescription("Search").assert(hasRole(Role.Button))
    }

    @Test
    fun segmentedRowIsAGroupOfRadioButtons() {
        var selected by mutableIntStateOf(0)
        show { SegmentedRow(listOf("PDF", "JPG", "PNG", "Text"), selected, { selected = it }) }
        compose.onAllNodes(hasRole(Role.RadioButton)).assertCountEquals(4)
        compose.onNodeWithText("JPG").performClick()
        assertThat(selected).isEqualTo(1)
        compose.onNodeWithText("JPG").assertIsSelected()
    }

    @Test
    fun segmentedRowReadsItsSpokenLabels() {
        show {
            SegmentedRow(
                listOf("Small", "Original"), 0, {},
                details = listOf("600 kB", "…"),
                spoken = listOf("Small, about 600 kB", "Original"),
            )
        }
        compose.onNodeWithText("Small").assert(hasContentDescription("Small, about 600 kB")).assert(hasRole(Role.RadioButton))
        compose.onNodeWithText("Original").assert(hasContentDescription("Original"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text widths; the legacy shadows measure a character as 1 px
    fun segmentedRowStacksRatherThanBreakAWord() {
        val options = listOf("Small", "Standard", "Original")
        val sizes = listOf("600 kB", "1.8 MB", "4.2 MB")
        var scale by mutableStateOf(1f)
        show {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                Box(Modifier.width(328.dp)) { SegmentedRow(options, 0, {}, details = sizes) }
            }
        }
        // At 1x the three sit side by side; at 2x on a phone "Original" no longer fits a third of the row.
        for ((fontScale, stacked) in listOf(1f to false, 2f to true)) {
            scale = fontScale
            compose.waitForIdle()
            val lefts = options.map { compose.onNodeWithText(it).getUnclippedBoundsInRoot().left }
            if (stacked) {
                assertWithMessage("at ${fontScale}x").that(lefts.toSet()).hasSize(1)
            } else {
                assertWithMessage("at ${fontScale}x").that(lefts).isInStrictOrder()
            }
            compose.onAllNodes(hasRole(Role.RadioButton)).assertCountEquals(3)
        }
    }

    @Test
    fun printedSwitchRowIsOneSwitch() {
        var checked by mutableStateOf(false)
        show { PrintedSwitchRow(checked, { checked = it }, "Searchable PDF", Modifier.testTag("row")) }
        compose.onNodeWithTag("row").assert(hasRole(Role.Switch)).assertIsOff().assertHeightIsAtLeast(56.dp).performClick()
        assertThat(checked).isTrue()
        compose.onNodeWithTag("row").assertIsOn()
    }

    @Test
    fun documentRowReadsOneSentenceWithActionsAndLongPressSelects() {
        var shared = false
        var selected = false
        show {
            DocumentRow(
                title = "Lease agreement",
                meta = "Tue 20 Oct · 14:32",
                pageCount = 8,
                onClick = {},
                onLongClick = { selected = true },
                flags = listOf("Text"),
                actions = listOf(RowAction("Share") { shared = true }),
                modifier = Modifier.testTag("row"),
            )
        }
        val row = compose.onNodeWithContentDescription("Lease agreement, 8 pages, Tue 20 Oct, 14:32, Text")
        row.performCustomAccessibilityActionWithLabel("Share")
        assertThat(shared).isTrue()
        compose.onNodeWithTag("row").performTouchInput { longClick() }
        assertThat(selected).isTrue()
    }

    @Test
    fun textFieldReportsItsError() {
        show { HeycamTextField("Receipts/", {}, label = "Folder name", error = "Use letters and numbers", modifier = Modifier.testTag("field")) }
        compose.onNodeWithTag("field").assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Use letters and numbers"))
    }

    @Test
    fun findBarSaysWhichMatchAndDisablesArrowsWithoutMatches() {
        var total by mutableIntStateOf(5)
        var next = 0
        show { FindBar("deposit", {}, current = 2, total = total, onPrevious = {}, onNext = { next++ }) }
        compose.onNodeWithContentDescription("Match 2 of 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next match").assertIsEnabled().performClick()
        assertThat(next).isEqualTo(1)
        total = 0
        compose.onNodeWithContentDescription("No matches").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next match").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous match").assertIsNotEnabled()
    }

    @Test
    fun loadBarAndStatusBannerReportProgressAndOfferTheirAction() {
        var retried = false
        show {
            Column {
                StatusBanner("Getting the scanner", progress = 0.4f)
                StatusBanner("No connection", isError = true, actionLabel = "Retry", onAction = { retried = true })
            }
        }
        compose.onNodeWithContentDescription("Getting the scanner")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.4f, 0f..1f)))
        compose.onNodeWithText("Retry").assert(hasRole(Role.Button)).performClick()
        assertThat(retried).isTrue()
    }

    @Test
    fun snackbarShowsTheMessageAndReportsUndo() {
        val host = SnackbarHostState()
        var result: SnackbarResult? = null
        show {
            val scope = rememberCoroutineScope()
            Column {
                TextButton("Delete", { scope.launch { result = host.showHeycamSnackbar("Page deleted", "Undo") } })
                HeycamSnackbarHost(host)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Page deleted").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(result).isEqualTo(SnackbarResult.ActionPerformed)
    }

    @Test
    fun snackbarDismissesItselfAfterItsTimeout() {
        val host = SnackbarHostState()
        var result: SnackbarResult? = null
        show {
            val scope = rememberCoroutineScope()
            Column {
                TextButton("Save", { scope.launch { result = host.showHeycamSnackbar("Saved") } })
                HeycamSnackbarHost(host)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Save").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Saved").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4_100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(result).isEqualTo(SnackbarResult.Dismissed)
    }

    @Test
    fun errorSnackbarPrintsOnlyItsMessage() {
        val host = SnackbarHostState()
        show {
            val scope = rememberCoroutineScope()
            Column {
                TextButton("Save", { scope.launch { host.showHeycamSnackbar("Not enough space", isError = true) } })
                HeycamSnackbarHost(host)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Save").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Not enough space").assertIsDisplayed()
        compose.onAllNodesWithText("error", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("error", substring = true, ignoreCase = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun sectionTabsAreSelectableTabs() {
        var tab by mutableIntStateOf(0)
        show { SectionTabs(listOf("Contents", "Tools"), tab, { tab = it }) }
        compose.onAllNodes(hasRole(Role.Tab)).assertCountEquals(2)
        compose.onNodeWithText("Tools").performClick()
        assertThat(tab).isEqualTo(1)
        compose.onNodeWithText("Tools").assertIsSelected()
    }

    @Test
    fun pageThumbReadsItsNumberAndMatchState() {
        show { PageThumb(2, current = true, marked = true, modifier = Modifier.testTag("thumb")) }
        compose.onNodeWithTag("thumb")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Page 2")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Has matches"))
            .assertIsSelected()
    }

    @Test
    fun infoButtonShowsItsTextInADialog() {
        show { InfoButton("Searchable PDF", "Lets you search and copy the text.") }
        compose.onNodeWithContentDescription("About Searchable PDF").performClick()
        compose.onNodeWithText("Lets you search and copy the text.").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onAllNodesWithText("Lets you search and copy the text.").assertCountEquals(0)
    }

    @Test
    fun dialogConfirmsAndDismisses() {
        var confirmed = false
        var dismissed = false
        show { HeycamDialog("Discard 3 pages?", "Discard", { confirmed = true }, { dismissed = true }) }
        compose.onNodeWithText("Discard 3 pages?").assert(isHeading)
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(confirmed).isTrue()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun dialogDismissButtonCanBeAnAnswerOfItsOwn() {
        val calls = mutableListOf<String>()
        show {
            HeycamDialog(
                title = "Text is still being read on 2 pages",
                confirmLabel = "Wait",
                onConfirm = { calls += "wait" },
                onDismissRequest = { calls += "closed" },
                dismissLabel = "Export without text",
                onDismiss = { calls += "without text" },
            )
        }
        compose.onNodeWithText("Export without text").performClick()
        compose.onNodeWithText("Wait").performClick()
        assertThat(calls).containsExactly("without text", "wait").inOrder()
    }

    @Test
    fun rowsReadAsOneSentence() {
        var opened = false
        show {
            Column {
                TextHitRow("Lease agreement", "…a deposit of four weeks…", 2..8, 3, onClick = { opened = true })
                ValueRow("Source code", onClick = {}, external = true)
                ValueRow("Scans", value = "182 MB", spokenValue = "182 megabytes")
            }
        }
        compose.onNodeWithContentDescription("Lease agreement, Page 3, …a deposit of four weeks…").performClick()
        assertThat(opened).isTrue()
        compose.onNodeWithContentDescription("Source code, Opens in your browser").assert(hasRole(Role.Button))
        compose.onNodeWithContentDescription("Scans, 182 megabytes").assertIsDisplayed()
    }

    @Test
    fun actionBarItemsAreLabelledButtons() {
        var rotated = false
        show {
            BottomActionBar {
                ActionBarItem(HeycamIcons.Rotate, "Rotate", { rotated = true })
                ActionBarItem(HeycamIcons.Delete, "Delete", {}, enabled = false, destructive = true)
            }
        }
        compose.onNodeWithText("Rotate").assert(hasRole(Role.Button)).assertHeightIsAtLeast(48.dp).performClick()
        assertThat(rotated).isTrue()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
    }

    @Composable
    private fun ScaledFont(scale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, scale), content = content)
    }
}
