package heycam.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens of the Tools tab on Home's paper, in both editions (PLAN 6.3).
 * Record: :feature:tools:recordRoborazziDebug   Verify (CI): :feature:tools:verifyRoborazziDebug
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h640dp-xhdpi")
class ToolsTabScreenshotTest(private val mode: ThemeMode) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun toolsTab() {
        compose.setContent {
            HeycamTheme(mode) {
                Box(Modifier.fillMaxSize().background(Heycam.colors.paper).testTag("screen")) { ToolsTab(onTool = {}) }
            }
        }
        compose.onNodeWithTag("screen").captureRoboImage("src/test/screenshots/tools_${mode.name.lowercase()}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun modes() = listOf(arrayOf<Any>(ThemeMode.DayPaper), arrayOf<Any>(ThemeMode.NightInk))
    }
}
