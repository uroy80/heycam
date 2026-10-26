package heycam.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.hypot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The launcher icon is one adaptive icon with vector layers (minSdk 28 never reads bitmap mipmaps), so a single set
 * serves every density. This renders it at mdpi to xxxhdpi as goldens, checks the themed (monochrome) layer exists,
 * and checks the artwork stays inside the 66 dp safe zone that every launcher mask keeps.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun rendersAtEveryDensity() {
        mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192).forEach { (density, px) ->
            RuntimeEnvironment.setQualifiers("+$density")
            listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round).forEach { id ->
                val icon = ContextCompat.getDrawable(context, id)
                assertThat(icon).isInstanceOf(AdaptiveIconDrawable::class.java)
                assertThat((icon as AdaptiveIconDrawable).monochrome).isNotNull()
            }
            val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, px, px)
            icon.draw(Canvas(bitmap))
            bitmap.captureRoboImage("src/test/screenshots/icon/ic_launcher_$density.png")
        }
    }

    @Test
    fun foregroundAndMonochromeStayInsideTheSafeZone() {
        RuntimeEnvironment.setQualifiers("+mdpi")
        listOf(R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_monochrome).forEach { id ->
            val layer = ContextCompat.getDrawable(context, id)!!
            val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
            layer.setBounds(0, 0, 108, 108)
            layer.draw(Canvas(bitmap))
            var inked = 0
            for (y in 0 until 108) for (x in 0 until 108) {
                if ((bitmap.getPixel(x, y) ushr 24) > 8) {
                    inked++
                    val distance = hypot(x + 0.5 - 54.0, y + 0.5 - 54.0)
                    assertWithMessage("${context.resources.getResourceEntryName(id)} pixel ($x, $y)")
                        .that(distance).isAtMost(33.0)
                }
            }
            assertThat(inked).isGreaterThan(300)
        }
    }
}
