package heycam.core.common

import android.util.Log
import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class AndroidHeycamLogTest {
    @Before fun clear() = ShadowLog.clear()

    @Test
    fun releaseBuildsDropDebugLines() {
        AndroidHeycamLog(debugEnabled = false).d("files", "swept 3 files")
        assertThat(ShadowLog.getLogs()).isEmpty()
        AndroidHeycamLog(debugEnabled = true).d("files", "swept 3 files")
        val line = ShadowLog.getLogs().single()
        assertThat(line.type).isEqualTo(Log.DEBUG)
        assertThat(line.tag).isEqualTo("heycam.files")
        assertThat(line.msg).isEqualTo("swept 3 files")
    }

    @Test
    fun errorsAreLoggedByClassOnlyNeverByMessage() {
        // Exception messages can carry paths or file names (P3); only the class name may reach logcat.
        val error = FileNotFoundException("/data/user/0/in.edgr.heycam/files/pages/Tax return 2026.jpg")
        val log = AndroidHeycamLog(debugEnabled = false)
        log.w("import", "could not read", error)
        log.e("export", "write failed", error)
        val lines = ShadowLog.getLogs()
        assertThat(lines.map { it.type }).containsExactly(Log.WARN, Log.ERROR).inOrder()
        assertThat(lines.map { it.msg }).containsExactly(
            "could not read (java.io.FileNotFoundException)",
            "write failed (java.io.FileNotFoundException)",
        ).inOrder()
        assertThat(lines.all { it.throwable == null }).isTrue()
    }

    @Test
    fun productionDispatchersAndClock() {
        assertThat(DefaultDispatcherProvider.io).isEqualTo(kotlinx.coroutines.Dispatchers.IO)
        assertThat(DefaultDispatcherProvider.default).isEqualTo(kotlinx.coroutines.Dispatchers.Default)
        val before = java.time.Instant.now()
        assertThat(SystemClock.now()).isAtLeast(before)
    }
}
