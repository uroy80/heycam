package heycam.app

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.util.zip.ZipFile
import org.junit.Test
import org.junit.runner.RunWith

/** Platform checks for the API 37 16 KB-page emulator (PLAN §6 level 4, 16 KB rule from 2027-02-01). */
@RunWith(AndroidJUnit4::class)
class DeviceChecksTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The suite runs on the ps16k image (heycam_spike_api37); anything else would not prove the 16 KB rule. */
    @Test
    fun pageSizeIs16k() {
        assertThat(Os.sysconf(OsConstants._SC_PAGESIZE)).isEqualTo(16_384L)
    }

    /** Every .so in the APK for this ABI loads; a library built for 4 KB pages fails here on a 16 KB device. */
    @Test
    fun everyPackagedNativeLibraryLoads() {
        val abi = Build.SUPPORTED_ABIS.first()
        val apks = listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty()
        val libraries = apks.flatMap { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().map { it.name }
                    .filter { it.startsWith("lib/$abi/") && it.endsWith(".so") }
                    .map { it.substringAfterLast('/').removePrefix("lib").removeSuffix(".so") }
                    .toList()
            }
        }.distinct()
        assertThat(libraries).isNotEmpty()
        val failures = libraries.mapNotNull { name ->
            runCatching { System.loadLibrary(name) }.exceptionOrNull()?.let { "$name: ${it.message}" }
        }
        assertThat(failures).isEmpty()
    }
}
