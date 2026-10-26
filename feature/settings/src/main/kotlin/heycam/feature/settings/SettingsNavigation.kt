package heycam.feature.settings

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable data object SettingsRoute
@Serializable data object AboutRoute        // version, Apache-2.0, source link, privacy policy, data statement
@Serializable data object LicensesRoute     // third-party + OFL font licences
@Serializable data object StorageRoute      // usage, empty trash, clear temporary files

fun NavGraphBuilder.settingsGraph(navController: NavController, onOpenTrash: () -> Unit) {
    composable<SettingsRoute> {
        SettingsRouteScreen(
            onBack = dropUnlessResumed { navController.popBackStack() },
            onOpenStorage = dropUnlessResumed { navController.navigate(StorageRoute) },
            onOpenAbout = dropUnlessResumed { navController.navigate(AboutRoute) },
        )
    }
    composable<AboutRoute> {
        AboutScreen(
            onBack = dropUnlessResumed { navController.popBackStack() },
            onOpenLicences = dropUnlessResumed { navController.navigate(LicensesRoute) },
        )
    }
    composable<LicensesRoute> {
        LicencesScreen(onBack = dropUnlessResumed { navController.popBackStack() })
    }
    composable<StorageRoute> {
        StorageRouteScreen(
            onBack = dropUnlessResumed { navController.popBackStack() },
            onOpenTrash = dropUnlessResumed { onOpenTrash() },
        )
    }
}
