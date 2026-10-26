package heycam.app

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import heycam.app.pick.DocumentTool
import heycam.app.pick.PickDocumentRoute
import heycam.core.model.ImportTarget
import heycam.core.scanner.ScanRequest
import heycam.feature.document.DocumentRoute
import heycam.feature.document.PageViewerRoute
import heycam.feature.export.ExportRoute
import heycam.feature.library.HomeRoute
import heycam.feature.library.SearchRoute
import heycam.feature.scan.ReviewRoute
import heycam.feature.scan.ScanEntry
import heycam.feature.tools.Tool
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How :app connects the features: after Review saves, the Tools rows, and entry-point requests. */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController
    private val scan = RecordingScanEntry()

    @Before
    fun setUp() {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = HomeRoute) {
                composable<HomeRoute> { Text("home") }
                composable<SearchRoute> { Text("search") }
                composable<ReviewRoute> { Text("review") }
                composable<DocumentRoute> { Text("document") }
                composable<PageViewerRoute> { Text("page") }
                dialog<ExportRoute> { Text("export") }
                dialog<PickDocumentRoute> { Text("pick") }
            }
        }
    }

    private fun backStack(): List<String> = nav.currentBackStack.value.mapNotNull { entry ->
        val destination = entry.destination
        when {
            destination.hasRoute<HomeRoute>() -> "home"
            destination.hasRoute<SearchRoute>() -> "search"
            destination.hasRoute<ReviewRoute>() -> "review"
            destination.hasRoute<DocumentRoute>() -> entry.toRoute<DocumentRoute>().let { "document ${it.documentId} text=${it.showText}" }
            destination.hasRoute<PageViewerRoute>() -> entry.toRoute<PageViewerRoute>().let { "page ${it.documentId}/${it.pageIndex}" }
            destination.hasRoute<ExportRoute>() -> "export ${entry.toRoute<ExportRoute>().documentIds}"
            destination.hasRoute<PickDocumentRoute>() -> "pick ${entry.toRoute<PickDocumentRoute>().tool}"
            else -> null // the graph itself
        }
    }

    @Test
    fun aNewDocumentReplacesReview() {
        rule.runOnIdle {
            nav.navigate(ReviewRoute("s1"))
            nav.showSavedDocument(42)
        }
        rule.runOnIdle { assertThat(backStack()).containsExactly("home", "document 42 text=false").inOrder() }
    }

    @Test
    fun pagesAddedToAnOpenDocumentReturnToIt() {
        rule.runOnIdle {
            nav.navigate(DocumentRoute(42))
            nav.navigate(ReviewRoute("s1"))
            nav.showSavedDocument(42)
        }
        rule.runOnIdle { assertThat(backStack()).containsExactly("home", "document 42 text=false").inOrder() }
    }

    @Test
    fun aRetakeReturnsToThePageViewer() {
        rule.runOnIdle {
            nav.navigate(DocumentRoute(42))
            nav.navigate(PageViewerRoute(42, 3))
            nav.navigate(ReviewRoute("s1"))
            nav.showSavedDocument(42)
        }
        rule.runOnIdle { assertThat(backStack()).containsExactly("home", "document 42 text=false", "page 42/3").inOrder() }
    }

    @Test
    fun documentToolsPickADocumentFirst() {
        rule.runOnIdle { handleTool(Tool.COMPRESS, scan, nav) }
        rule.runOnIdle { assertThat(backStack()).containsExactly("home", "pick COMPRESS").inOrder() }
        rule.runOnIdle { nav.openPicked(DocumentTool.COMPRESS, 9) }
        rule.runOnIdle { assertThat(backStack()).containsExactly("home", "export [9]").inOrder() }

        rule.runOnIdle { nav.popBackStack(); handleTool(Tool.EXTRACT_TEXT, scan, nav) }
        rule.runOnIdle { nav.openPicked(DocumentTool.EXTRACT_TEXT, 9) }
        rule.runOnIdle { assertThat(backStack()).containsExactly("home", "document 9 text=true").inOrder() }

        rule.runOnIdle { nav.popBackStack(); handleTool(Tool.PRINT, scan, nav) }
        rule.runOnIdle { nav.openPicked(DocumentTool.PRINT, 9) }
        rule.runOnIdle { assertThat(backStack()).containsExactly("home", "export [9]").inOrder() }
        assertThat(scan.calls).isEmpty()
    }

    @Test
    fun captureToolsStartANewDocument() {
        rule.runOnIdle {
            handleTool(Tool.SCAN_TO_PDF, scan, nav)
            handleTool(Tool.IMPORT_PHOTOS, scan, nav)
            handleTool(Tool.HEYPDF_LINK, scan, nav)
        }
        assertThat(scan.calls).containsExactly(
            "scan ${ImportTarget.NewDocument()} ${ScanRequest()}",
            "import ${ImportTarget.NewDocument()}",
        ).inOrder()
        rule.runOnIdle { assertThat(backStack()).containsExactly("home") }
    }

    @Test
    fun entryPointsReachTheirScreens() {
        val skipped = mutableListOf<Int>()
        val image = Uri.parse("content://media/external/images/1")
        rule.runOnIdle {
            handleLaunch(LaunchAction.Scan, scan, nav, skipped::add)
            handleLaunch(LaunchAction.Import, scan, nav, skipped::add)
            handleLaunch(LaunchAction.Share(listOf(image), skipped = 2), scan, nav, skipped::add)
            handleLaunch(LaunchAction.Share(emptyList(), skipped = 1), scan, nav, skipped::add)
            handleLaunch(LaunchAction.Search, scan, nav, skipped::add)
        }
        assertThat(scan.calls).containsExactly(
            "scan ${ImportTarget.NewDocument()} ${ScanRequest()}",
            "import ${ImportTarget.NewDocument()}",
            "shared [$image]",
        ).inOrder()
        assertThat(skipped).containsExactly(2, 1).inOrder()
        rule.runOnIdle { assertThat(backStack()).containsExactly("home", "search").inOrder() }
    }
}

private class RecordingScanEntry : ScanEntry {
    val calls = mutableListOf<String>()
    override fun scan(target: ImportTarget, request: ScanRequest) { calls += "scan $target $request" }
    override fun importPhotos(target: ImportTarget) { calls += "import $target" }
    override fun importShared(uris: List<Uri>) { calls += "shared $uris" }
}
