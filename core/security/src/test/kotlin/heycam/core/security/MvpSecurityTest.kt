package heycam.core.security

import androidx.activity.ComponentActivity
import org.robolectric.RobolectricTestRunner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MvpSecurityTest {

    @Test
    fun disabledLockNeverLocks() = runTest {
        val lock = DisabledAppLock()
        assertThat(lock.state.value).isEqualTo(LockState.DISABLED)

        lock.onBackground(elapsedRealtimeMs = 1_000)
        lock.onForeground(elapsedRealtimeMs = 10_000_000)
        assertThat(lock.state.value).isEqualTo(LockState.DISABLED)

        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertThat(lock.unlock(activity)).isEqualTo(UnlockResult.Success)
        assertThat(lock.state.value).isEqualTo(LockState.DISABLED)
    }

    @Test
    fun disabledLockReportsUnsupported() {
        assertThat(DisabledAppLock().availability()).isEqualTo(LockAvailability.UNSUPPORTED)
    }

    @Test
    fun windowIsNeverSecure() {
        assertThat(NeverSecureWindowPolicy().secure.value).isFalse()
    }
}
