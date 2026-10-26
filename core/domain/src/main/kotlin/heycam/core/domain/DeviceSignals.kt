package heycam.core.domain

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The phone's languages in the user's order (Settings → Languages), for the OCR script default (D20). */
fun interface DeviceLocales {
    fun current(): List<Locale>
}

class SystemDeviceLocales @Inject constructor() : DeviceLocales {
    override fun current(): List<Locale> = LocaleList.getDefault().let { list -> List(list.size()) { list[it] } }
}

/** Suspends until the phone has a network with internet, so a model download that failed offline can try again. */
fun interface NetworkMonitor {
    suspend fun awaitOnline()
}

/**
 * ACCESS_NETWORK_STATE reaches the app through ML Kit's manifest (PLAN 0); heycam declares no permission itself, hence
 * the suppression. Without the grant the callback cannot register and the caller simply tries again.
 */
class ConnectivityNetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) : NetworkMonitor {
    @SuppressLint("MissingPermission")
    override suspend fun awaitOnline() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        var registered: ConnectivityManager.NetworkCallback? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }
                try {
                    connectivity.registerDefaultNetworkCallback(callback)
                    registered = callback
                } catch (_: RuntimeException) { // no ACCESS_NETWORK_STATE or too many callbacks: just try again
                    continuation.resume(Unit)
                }
            }
        } finally {
            registered?.let { callback ->
                try {
                    connectivity.unregisterNetworkCallback(callback)
                } catch (_: IllegalArgumentException) { // already unregistered
                }
            }
        }
    }
}
