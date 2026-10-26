package heycam.app

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import heycam.app.pick.DocumentTool
import heycam.app.pick.PickDocumentRoute
import heycam.core.designsystem.BottomBarInset
import heycam.core.designsystem.HeycamSnackbarHost
import heycam.core.designsystem.LocalBottomBarInset
import heycam.core.designsystem.showHeycamSnackbar
import heycam.core.model.ImportTarget
import heycam.feature.library.navigateToSearch
import heycam.feature.scan.ScanEntry
import heycam.feature.scan.ScanMessage
import heycam.feature.scan.navigateToReview
import heycam.feature.scan.rememberScanEntry
import heycam.feature.scan.text
import heycam.feature.tools.Tool
import kotlinx.coroutines.launch

/**
 * The app shell: one [ScanEntry] above the NavHost (it owns the scanner and Photo Picker launchers, PLAN §4.12),
 * requests from shortcuts, the tile and other apps, and the scan feedback snackbars.
 */
@Composable
fun HeycamApp(launchAction: LaunchAction?, onLaunchHandled: (LaunchAction) -> Unit) {
    val navController = rememberNavController()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    fun show(message: String, isError: Boolean) {
        scope.launch { snackbars.showHeycamSnackbar(message, isError = isError) }
    }
    val scan = rememberScanEntry(
        onStaged = { sessionId -> navController.navigateToReview(sessionId) },
        // Only a failure gets the accent bar; "2 images skipped" and an unavailable scanner are plain notices.
        onMessage = { message -> show(message.text(resources), isError = message is ScanMessage.Failed) },
    )
    LaunchedEffect(launchAction) {
        val action = launchAction ?: return@LaunchedEffect
        handleLaunch(action, scan, navController, onSkipped = { show(resources.imagesSkipped(it), isError = false) })
        onLaunchHandled(action)
    }
    val bottomBars = remember { BottomBarInset() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBottomBarInset provides bottomBars) {
            HeycamNavHost(navController = navController, scan = scan, modifier = Modifier.fillMaxSize())
        }
        ShellSnackbarHost(snackbars, bottomBars, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * The shell's snackbars print above the bottom bar of whatever screen is showing (Home's Scan and Import, a
 * document's page tools, Review's controls), which each report their height; with no bar, above the navigation bar.
 */
@Composable
internal fun ShellSnackbarHost(hostState: SnackbarHostState, bottomBars: BottomBarInset, modifier: Modifier = Modifier) {
    val bar = bottomBars.height
    HeycamSnackbarHost(
        hostState = hostState,
        modifier = modifier.then(if (bar > 0.dp) Modifier.padding(bottom = bar) else Modifier.navigationBarsPadding()),
    )
}

/** Shortcuts and the tile start a new document; shared images go to Review as a new document (PLAN F3, F11). */
internal fun handleLaunch(action: LaunchAction, scan: ScanEntry, navController: NavController, onSkipped: (count: Int) -> Unit) {
    when (action) {
        LaunchAction.Scan -> scan.scan(ImportTarget.NewDocument())
        LaunchAction.Import -> scan.importPhotos(ImportTarget.NewDocument())
        LaunchAction.Search -> navController.navigateToSearch()
        is LaunchAction.Share -> {
            if (action.images.isNotEmpty()) scan.importShared(action.images)
            if (action.skipped > 0) onSkipped(action.skipped)
        }
    }
}

/** The Tools tab's rows (PLAN F9). */
internal fun handleTool(tool: Tool, scan: ScanEntry, navController: NavController) {
    when (tool) {
        Tool.SCAN_TO_PDF -> scan.scan(ImportTarget.NewDocument())
        Tool.IMPORT_PHOTOS -> scan.importPhotos(ImportTarget.NewDocument())
        Tool.COMPRESS -> navController.navigate(PickDocumentRoute(DocumentTool.COMPRESS))
        Tool.EXTRACT_TEXT -> navController.navigate(PickDocumentRoute(DocumentTool.EXTRACT_TEXT))
        Tool.PRINT -> navController.navigate(PickDocumentRoute(DocumentTool.PRINT))
        // The Tools tab shows the heypdf disclosure (P9) and opens the browser itself.
        Tool.HEYPDF_LINK -> Unit
    }
}

private fun Resources.imagesSkipped(count: Int): String = getQuantityString(R.plurals.app_images_skipped, count, count)
