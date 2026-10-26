package heycam.core.ocr

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.UnsupportedReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Google Play services checks and module downloads for the text-recognition models (verified in gate G1). */
internal object ModelInstall {
    /** Unbundled text recognition lives in Play services; without it heycam can never read text. */
    fun playServices(context: Context): UnsupportedReason? =
        when (GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context)) {
            ConnectionResult.SUCCESS -> null
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_UPDATING -> UnsupportedReason.PLAY_SERVICES_OUTDATED
            else -> UnsupportedReason.NO_PLAY_SERVICES
        }

    /**
     * Whether there is a network that can reach the internet. Unknown counts as online so a download is tried.
     * ACCESS_NETWORK_STATE reaches the app through ML Kit's manifest (PLAN 0); heycam declares no permission itself,
     * hence the suppression, and a missing grant only means "unknown".
     */
    @SuppressLint("MissingPermission")
    fun online(context: Context): Boolean = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        connectivity == null || capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: SecurityException) {
        true
    }

    /**
     * Installs [apis] with progress. Emits [ModuleState.Ready] when they are on the phone and completes after any
     * terminal state (Ready or Failed).
     */
    fun install(client: ModuleInstallClient, apis: List<OptionalModuleApi>): Flow<ModuleState> = callbackFlow {
        val listener = InstallStatusListener { update ->
            val state = stateOf(update) ?: return@InstallStatusListener
            trySend(state)
            if (state.isTerminal()) channel.close()
        }
        // trySend: the listener may already have closed the channel with a terminal state.
        try {
            val request = ModuleInstallRequest.newBuilder().apply { apis.forEach(::addApi) }.setListener(listener).build()
            if (client.installModules(request).await().areModulesAlreadyInstalled()) {
                trySend(ModuleState.Ready)
                channel.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trySend(failureOf(e))
            channel.close()
        }
        awaitClose { client.unregisterListener(listener) }
    }

    /** Maps one Play services progress update; null for updates that carry no news (unknown state). */
    fun stateOf(update: ModuleInstallStatusUpdate): ModuleState? = when (update.installState) {
        InstallState.STATE_PENDING -> ModuleState.Downloading(0, update.progressInfo?.totalBytesToDownload?.takeIf { it > 0 })
        InstallState.STATE_DOWNLOADING -> update.progressInfo.let { progress ->
            ModuleState.Downloading(progress?.bytesDownloaded ?: 0, progress?.totalBytesToDownload?.takeIf { it > 0 })
        }
        InstallState.STATE_INSTALLING -> ModuleState.Installing
        InstallState.STATE_COMPLETED -> ModuleState.Ready
        InstallState.STATE_CANCELED -> ModuleState.Failed(ModuleFailure.CANCELLED, retryable = true)
        // Play services pauses a download while it waits for a network.
        InstallState.STATE_DOWNLOAD_PAUSED -> ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true)
        InstallState.STATE_FAILED -> ModuleState.Failed(failureOf(update.errorCode), retryable = true)
        else -> null
    }

    fun failureOf(error: Exception): ModuleState.Failed =
        ModuleState.Failed(failureOf((error as? ApiException)?.statusCode ?: CommonStatusCodes.ERROR), retryable = true)

    private fun failureOf(statusCode: Int): ModuleFailure = when (statusCode) {
        CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT -> ModuleFailure.NO_NETWORK
        CommonStatusCodes.CANCELED -> ModuleFailure.CANCELLED
        else -> ModuleFailure.UNKNOWN
    }

    fun ModuleState.isTerminal(): Boolean =
        this == ModuleState.Ready || this is ModuleState.Failed || this is ModuleState.Unsupported
}
