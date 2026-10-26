package heycam.feature.export

import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/**
 * Export sheet as a dialog destination (square sheet, ink rule on top): format, size, page size, searchable, name,
 * Save/Share/Print. [compress] opens it with the Small preset (Tools → Compress).
 */
@Serializable data class ExportRoute(val documentIds: List<Long>, val compress: Boolean = false)

fun NavController.navigateToExport(documentIds: List<Long>, compress: Boolean = false) =
    navigate(ExportRoute(documentIds, compress))

fun NavGraphBuilder.exportGraph(navController: NavController) {
    dialog<ExportRoute>(
        dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) { entry ->
        val route = entry.toRoute<ExportRoute>()
        ExportSheetRoute(
            args = ExportArgs(route.documentIds, route.compress),
            onDismiss = dropUnlessResumed { navController.popBackStack() },
        )
    }
}
