package heycam.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import heycam.core.designsystem.BottomBarInset
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.LocalBottomBarInset
import heycam.core.designsystem.ReportBottomBar
import heycam.core.designsystem.ThemeMode
import heycam.core.designsystem.showHeycamSnackbar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The shell's scan messages print above the showing screen's bottom bar, however tall it has grown. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h740dp")
class ShellSnackbarTest {
    @get:Rule val rule = createComposeRule()

    private fun show(bars: BottomBarInset, host: SnackbarHostState, bar: () -> Boolean) {
        rule.setContent {
            HeycamTheme(ThemeMode.DayPaper) {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalBottomBarInset provides bars) {
                        if (bar()) {
                            // A bottom bar stacked at 200% text is about this tall.
                            ReportBottomBar(Modifier.align(Alignment.BottomCenter)) {
                                Box(Modifier.fillMaxWidth().height(250.dp).testTag("bar"))
                            }
                        }
                    }
                    ShellSnackbarHost(host, bars, Modifier.align(Alignment.BottomCenter))
                }
                LaunchedEffect(Unit) { host.showHeycamSnackbar("1 image skipped") }
            }
        }
    }

    @Test
    fun snackbarPrintsAboveATallBottomBar() {
        val bars = BottomBarInset()
        rule.mainClock.autoAdvance = false // the snackbar stays up while it is measured
        show(bars, SnackbarHostState()) { true }
        rule.mainClock.advanceTimeBy(500)
        val bar = rule.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val slip = rule.onNodeWithText("1 image skipped").getUnclippedBoundsInRoot()
        assertThat(bars.height).isEqualTo(250.dp)
        assertThat(slip.bottom).isAtMost(bar.top)
    }

    @Test
    fun withNoBarTheSnackbarSitsAtTheBottom() {
        rule.mainClock.autoAdvance = false
        show(BottomBarInset(), SnackbarHostState()) { false }
        rule.mainClock.advanceTimeBy(500)
        val slip = rule.onNodeWithText("1 image skipped").getUnclippedBoundsInRoot()
        assertThat(slip.bottom).isGreaterThan(740.dp - 250.dp)
    }

    @Test
    fun aBarThatLeavesReportsNothing() {
        val bars = BottomBarInset()
        var showBar by mutableStateOf(true)
        show(bars, SnackbarHostState()) { showBar }
        rule.waitForIdle()
        assertThat(bars.height).isEqualTo(250.dp)
        showBar = false
        rule.waitForIdle()
        rule.onNodeWithTag("bar").assertDoesNotExist()
        assertThat(bars.height).isEqualTo(0.dp)
    }
}
