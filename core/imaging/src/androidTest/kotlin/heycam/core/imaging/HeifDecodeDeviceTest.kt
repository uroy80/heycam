package heycam.core.imaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HEIF needs the platform codec, which Robolectric does not have, so this one import case runs on a device:
 * the importer decodes every copied file through [Codec.decode].
 */
@RunWith(AndroidJUnit4::class)
class HeifDecodeDeviceTest {
    @Test
    fun heifDecodesUprightAtFullSize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "page.heic")
        instrumentation.context.assets.open("page.heic").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            val bitmap = Codec.decode(file)
            assertThat(bitmap.width to bitmap.height).isEqualTo(300 to 200)
            assertThat(luma(bitmap.getPixel(150, 100))).isLessThan(60)
            assertThat(luma(bitmap.getPixel(150, 20))).isGreaterThan(200)
            bitmap.recycle()
        } finally {
            file.delete()
        }
    }
}
