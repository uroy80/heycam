package heycam.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import heycam.app.pick.DocumentTool
import heycam.app.pick.PickDocumentRoute
import heycam.feature.document.DocumentRoute
import heycam.feature.document.navigateToDocument
import heycam.feature.export.ExportRoute
import heycam.feature.export.navigateToExport
import heycam.feature.library.HomeRoute
import heycam.feature.library.SearchRoute
import heycam.feature.library.navigateToSearch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The binding route types (List<Long>, nullable Int and String, enums) survive Navigation's type-safe encoding. */
@RunWith(AndroidJUnit4::class)
class RouteArgsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun routesRoundTrip() {
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = HomeRoute) {
                composable<HomeRoute> { Text("home") }
                composable<DocumentRoute> {
                    val r = it.toRoute<DocumentRoute>()
                    Text("doc ${r.documentId} ${r.pageIndex} ${r.showText} ${r.highlight}")
                }
                composable<SearchRoute> { Text("search ${it.toRoute<SearchRoute>().initialQuery}") }
                dialog<ExportRoute> { Text("export ${it.toRoute<ExportRoute>().documentIds}") }
                dialog<PickDocumentRoute> { Text("pick ${it.toRoute<PickDocumentRoute>().tool}") }
            }
        }
        rule.runOnIdle { nav.navigateToExport(listOf(3L, 5L)) }
        rule.onNodeWithText("export [3, 5]").assertExists()
        rule.runOnIdle { nav.popBackStack(); nav.navigateToDocument(7L, pageIndex = 2, showText = true, highlight = "दुनिया 東京") }
        rule.onNodeWithText("doc 7 2 true दुनिया 東京").assertExists()
        rule.runOnIdle { nav.navigateToDocument(8L) }
        rule.onNodeWithText("doc 8 null false null").assertExists()
        rule.runOnIdle { nav.navigateToSearch("a/b?c&d") }
        rule.onNodeWithText("search a/b?c&d").assertExists()
        rule.runOnIdle { nav.navigate(PickDocumentRoute(DocumentTool.EXTRACT_TEXT)) }
        rule.onNodeWithText("pick EXTRACT_TEXT").assertExists()
    }
}
