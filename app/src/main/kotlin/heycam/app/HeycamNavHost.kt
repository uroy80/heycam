package heycam.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.toRoute
import heycam.app.pick.DocumentTool
import heycam.app.pick.PickDocumentRoute
import heycam.app.pick.documentPicker
import heycam.core.model.DocumentId
import heycam.core.model.ImportTarget
import heycam.core.model.PageId
import heycam.core.scanner.ScanRequest
import heycam.feature.document.DocumentRoute
import heycam.feature.document.PageViewerRoute
import heycam.feature.document.documentGraph
import heycam.feature.document.navigateToDocument
import heycam.feature.export.ExportRoute
import heycam.feature.export.exportGraph
import heycam.feature.export.navigateToExport
import heycam.feature.library.HomeRoute
import heycam.feature.library.TrashRoute
import heycam.feature.library.libraryGraph
import heycam.feature.scan.ReviewRoute
import heycam.feature.scan.ScanEntry
import heycam.feature.scan.ScannerStatusBar
import heycam.feature.scan.scanGraph
import heycam.feature.settings.SettingsRoute
import heycam.feature.settings.settingsGraph
import heycam.feature.tools.ToolsTab
import heycam.feature.tools.toolsGraph

/** The only place where routes of different features meet (PLAN §3.2, §4.12). */
@Composable
fun HeycamNavHost(navController: NavHostController, scan: ScanEntry, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = HomeRoute, modifier = modifier) {
        heycamGraph(navController, scan)
    }
}

internal fun NavGraphBuilder.heycamGraph(navController: NavController, scan: ScanEntry) {
    libraryGraph(
        navController = navController,
        toolsTab = { ToolsTab(onTool = { tool -> handleTool(tool, scan, navController) }) },
        onOpenDocument = { documentId, pageIndex, highlight ->
            navController.navigateToDocument(documentId, pageIndex, showText = highlight != null, highlight = highlight)
        },
        onScan = { scan.scan(ImportTarget.NewDocument()) },
        onImport = { scan.importPhotos(ImportTarget.NewDocument()) },
        onExport = { documentIds -> navController.navigateToExport(documentIds) },
        onOpenSettings = { navController.navigate(SettingsRoute) },
        scanStatus = { ScannerStatusBar(scan) },
    )
    scanGraph(
        navController = navController,
        onSaved = { documentId -> navController.showSavedDocument(documentId) },
        onDiscarded = { navController.popBackStack() },
    )
    documentGraph(
        navController = navController,
        onScanMore = { documentId -> scan.scan(ImportTarget.AppendTo(DocumentId(documentId))) },
        onImportMore = { documentId -> scan.importPhotos(ImportTarget.AppendTo(DocumentId(documentId))) },
        onRetake = { pageId -> scan.scan(ImportTarget.Replace(PageId(pageId)), ScanRequest(pageLimit = 1)) },
        onExport = { documentIds -> navController.navigateToExport(documentIds) },
    )
    exportGraph(navController)
    toolsGraph()
    settingsGraph(navController, onOpenTrash = { navController.navigate(TrashRoute) })
    documentPicker(
        onPicked = { tool, documentId -> navController.openPicked(tool, documentId) },
        onDismiss = { navController.popBackStack() },
    )
}

/**
 * After Review saves: pages added to or retaken in an open document return to that document (or its page viewer);
 * a new document replaces Review, so Back from it goes to where the scan started.
 */
internal fun NavController.showSavedDocument(documentId: Long) {
    if (previousBackStackEntry?.documentIdOrNull() == documentId) {
        popBackStack()
    } else {
        navigate(DocumentRoute(documentId)) { popUpTo<ReviewRoute> { inclusive = true } }
    }
}

private fun NavBackStackEntry.documentIdOrNull(): Long? = when {
    destination.hasRoute<DocumentRoute>() -> toRoute<DocumentRoute>().documentId
    destination.hasRoute<PageViewerRoute>() -> toRoute<PageViewerRoute>().documentId
    else -> null
}

/**
 * The tools that work on one document (PLAN F9) continue once it is picked: Compress and Print open the export sheet,
 * Extract text the document's text view. The picker leaves the back stack.
 */
internal fun NavController.openPicked(tool: DocumentTool, documentId: Long) {
    val route: Any = when (tool) {
        DocumentTool.COMPRESS -> ExportRoute(listOf(documentId), compress = true)
        DocumentTool.PRINT -> ExportRoute(listOf(documentId))
        DocumentTool.EXTRACT_TEXT -> DocumentRoute(documentId, showText = true)
    }
    navigate(route) { popUpTo<PickDocumentRoute> { inclusive = true } }
}
