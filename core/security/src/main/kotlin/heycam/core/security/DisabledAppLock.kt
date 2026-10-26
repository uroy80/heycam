package heycam.core.security

import androidx.activity.ComponentActivity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The MVP lock: there is no lock. The state is always [LockState.DISABLED], so the app never shows a lock screen,
 * and [unlock] succeeds without asking. v1.1 replaces this binding with a BiometricPrompt implementation.
 */
@Singleton
class DisabledAppLock @Inject constructor() : AppLock {
    private val _state = MutableStateFlow(LockState.DISABLED)
    override val state: StateFlow<LockState> = _state.asStateFlow()

    override fun onBackground(elapsedRealtimeMs: Long) = Unit

    override fun onForeground(elapsedRealtimeMs: Long) = Unit

    override suspend fun unlock(activity: ComponentActivity): UnlockResult = UnlockResult.Success

    /** This build cannot lock at all, whatever the device supports. */
    override fun availability(): LockAvailability = LockAvailability.UNSUPPORTED
}

/** MVP: the window is never FLAG_SECURE ("Hide in Recents" ships in v1.1). */
@Singleton
class NeverSecureWindowPolicy @Inject constructor() : SecureWindowPolicy {
    override val secure: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
}
