package heycam.core.testing.security

import androidx.activity.ComponentActivity
import heycam.core.security.AppLock
import heycam.core.security.LockAvailability
import heycam.core.security.LockState
import heycam.core.security.SecureWindowPolicy
import heycam.core.security.UnlockResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-written AppLock for tests (PLAN D26). Starts [initial]; when enabled it locks on foreground once
 * [relockAfterMs] has passed since the last background, and [unlock] answers with [nextUnlockResult].
 */
class FakeAppLock(
    initial: LockState = LockState.DISABLED,
    var relockAfterMs: Long = 0,
    var nextUnlockResult: UnlockResult = UnlockResult.Success,
    var availability: LockAvailability = LockAvailability.AVAILABLE,
) : AppLock {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<LockState> = _state.asStateFlow()

    private var backgroundAt: Long? = null
    var unlockCalls: Int = 0
        private set

    fun setState(state: LockState) {
        _state.value = state
    }

    override fun onBackground(elapsedRealtimeMs: Long) {
        backgroundAt = elapsedRealtimeMs
    }

    override fun onForeground(elapsedRealtimeMs: Long) {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (_state.value == LockState.UNLOCKED && elapsedRealtimeMs - since >= relockAfterMs) _state.value = LockState.LOCKED
    }

    override suspend fun unlock(activity: ComponentActivity): UnlockResult {
        unlockCalls++
        val result = nextUnlockResult
        if (result == UnlockResult.Success && _state.value == LockState.LOCKED) _state.value = LockState.UNLOCKED
        return result
    }

    override fun availability(): LockAvailability = availability
}

/** SecureWindowPolicy whose value a test sets directly. */
class FakeSecureWindowPolicy(initial: Boolean = false) : SecureWindowPolicy {
    private val _secure = MutableStateFlow(initial)
    override val secure: StateFlow<Boolean> = _secure.asStateFlow()

    fun setSecure(secure: Boolean) {
        _secure.value = secure
    }
}
