package heycam.feature.library

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import heycam.feature.library.folder.FolderDestination
import heycam.feature.library.home.HomeDestination
import heycam.feature.library.search.SearchDestination
import heycam.feature.library.trash.TrashDestination
import kotlinx.serialization.Serializable

/** Home = masthead + dateline + tabs (Contents | Tools). Contents is ours; Tools is a slot filled by :feature:tools. */
@Serializable data object HomeRoute
@Serializable data class FolderRoute(val folderId: Long)
@Serializable data class SearchRoute(val initialQuery: String? = null)
@Serializable data object TrashRoute

fun NavController.navigateToSearch(query: String? = null) = navigate(SearchRoute(query))

/**
 * Home, folders, search and trash.
 *
 * @param scanStatus shown under the dateline on Home: :feature:scan's scanner download / unavailable bar (S6).
 *   Defaults to nothing so the graph composes without the scan feature.
 */
fun NavGraphBuilder.libraryGraph(
    navController: NavController,
    toolsTab: @Composable () -> Unit,
    onOpenDocument: (documentId: Long, pageIndex: Int?, highlight: String?) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onExport: (documentIds: List<Long>) -> Unit,
    onOpenSettings: () -> Unit,
    scanStatus: @Composable () -> Unit = {},
) {
    composable<HomeRoute> {
        HomeDestination(
            toolsTab = toolsTab,
            scanStatus = scanStatus,
            onSearch = { navController.navigateToSearch() },
            onSettings = onOpenSettings,
            onScan = onScan,
            onImport = onImport,
            onOpenDocument = { onOpenDocument(it.value, null, null) },
            onExport = { ids -> onExport(ids.map { it.value }) },
            onOpenFolder = { navController.navigate(FolderRoute(it.value)) },
            onOpenTrash = { navController.navigate(TrashRoute) },
        )
    }
    composable<FolderRoute> {
        FolderDestination(
            onBack = { navController.popBackStack() },
            onOpenDocument = { onOpenDocument(it.value, null, null) },
            onOpenFolder = { navController.navigate(FolderRoute(it.value)) },
            onExport = { ids -> onExport(ids.map { it.value }) },
        )
    }
    composable<SearchRoute> {
        SearchDestination(
            onBack = { navController.popBackStack() },
            onOpen = { id, page, highlight -> onOpenDocument(id.value, page, highlight) },
        )
    }
    composable<TrashRoute> {
        TrashDestination(onBack = { navController.popBackStack() })
    }
}
