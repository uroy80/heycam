package heycam.feature.document

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import heycam.feature.document.viewer.PageViewerDestination
import kotlinx.serialization.Serializable

/** Page grid (reorder, rotate, delete, rename, move) with a Pages | Text toggle; Text = recognised text + find bar. */
@Serializable data class DocumentRoute(
    val documentId: Long,
    val pageIndex: Int? = null,
    val showText: Boolean = false,
    val highlight: String? = null,
)
@Serializable data class PageViewerRoute(val documentId: Long, val pageIndex: Int)

fun NavController.navigateToDocument(documentId: Long, pageIndex: Int? = null, showText: Boolean = false, highlight: String? = null) =
    navigate(DocumentRoute(documentId, pageIndex, showText, highlight))

fun NavGraphBuilder.documentGraph(
    navController: NavController,
    onScanMore: (documentId: Long) -> Unit,
    onImportMore: (documentId: Long) -> Unit,
    onRetake: (pageId: Long) -> Unit,
    onExport: (documentIds: List<Long>) -> Unit,
) {
    composable<DocumentRoute> { entry ->
        val documentId = entry.toRoute<DocumentRoute>().documentId
        DocumentDestination(
            onBack = { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
            onOpenPage = { index -> navController.navigate(PageViewerRoute(documentId, index)) },
            onScanMore = { onScanMore(documentId) },
            onImportMore = { onImportMore(documentId) },
            onExport = { onExport(listOf(documentId)) },
        )
    }
    composable<PageViewerRoute> { entry ->
        PageViewerDestination(
            onBack = { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
            onRetake = onRetake,
        )
    }
}
