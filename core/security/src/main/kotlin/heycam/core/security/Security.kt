package heycam.core.security

import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.StateFlow

/** MVP binds DisabledAppLock. v1.1: BiometricPrompt (BIOMETRIC_WEAK or DEVICE_CREDENTIAL); a UI gate, not encryption. */
interface AppLock {
    val state: StateFlow<LockState>
    fun onBackground(elapsedRealtimeMs: Long)
    fun onForeground(elapsedRealtimeMs: Long)
    suspend fun unlock(activity: ComponentActivity): UnlockResult
    fun availability(): LockAvailability
}

enum class LockState { DISABLED, LOCKED, UNLOCKED }

sealed interface UnlockResult {
    data object Success : UnlockResult
    data object Cancelled : UnlockResult
    data class Error(val code: Int) : UnlockResult
}

enum class LockAvailability { AVAILABLE, NOTHING_ENROLLED, UNSUPPORTED }

/** FLAG_SECURE on the activity window while true (v1.1 setting "Hide in Recents"). */
interface SecureWindowPolicy {
    val secure: StateFlow<Boolean>
}
