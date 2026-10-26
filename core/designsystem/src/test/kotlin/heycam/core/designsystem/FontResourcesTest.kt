package heycam.core.designsystem

import android.content.Context
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.ByteBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The build tools accept any bytes as a font, so a truncated or wrong file would only fail on a phone. This loads
 * each bundled font through the platform (native graphics), checks the TrueType header and the variation axes the
 * type roles rely on, and checks that every family's OFL text ships with it (OFL condition 2).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val fonts = mapOf(
        R.font.fraunces to setOf("wght", "opsz"),
        R.font.fraunces_italic to setOf("wght", "opsz"),
        R.font.libre_franklin to setOf("wght"),
        R.font.ibm_plex_mono_regular to emptySet(),
        R.font.ibm_plex_mono_medium to emptySet(),
    )

    @Test
    fun everyFontLoadsThroughThePlatform() {
        fonts.keys.forEach { id ->
            assertThat(ResourcesCompat.getFont(context, id)).isNotNull()
        }
    }

    @Test
    fun everyFontIsTrueTypeWithTheAxesTheTypeRolesUse() {
        fonts.forEach { (id, axes) ->
            val bytes = context.resources.openRawResource(id).use { it.readBytes() }
            val name = context.resources.getResourceEntryName(id)
            val font = ByteBuffer.wrap(bytes)
            assertThat(font.getInt(0)).isEqualTo(0x00010000)
            val tables = tableOffsets(font)
            assertThat(tables.keys).containsAtLeast("cmap", "glyf", "head", "name")
            if (axes.isEmpty()) {
                assertThat(tables).doesNotContainKey("fvar")
            } else {
                assertThat(axisTags(font, tables.getValue("fvar"))).containsAtLeastElementsIn(axes)
            }
            assertWithMessage(name).that(bytes.size).isGreaterThan(100_000)
        }
    }

    @Test
    fun eachFamilyShipsItsOflText() {
        val licences = context.assets.list("licences").orEmpty().toSet()
        assertThat(licences).containsAtLeast("fraunces-OFL.txt", "libre-franklin-OFL.txt", "ibm-plex-mono-OFL.txt")
        listOf("fraunces-OFL.txt", "libre-franklin-OFL.txt", "ibm-plex-mono-OFL.txt").forEach { file ->
            val text = context.assets.open("licences/$file").bufferedReader().use { it.readText() }
            assertThat(text).contains("SIL OPEN FONT LICENSE Version 1.1")
        }
        val plex = context.assets.open("licences/ibm-plex-mono-OFL.txt").bufferedReader().use { it.readText() }
        assertThat(plex).contains("Reserved Font Name \"Plex\"")
    }

    private fun tableOffsets(font: ByteBuffer): Map<String, Int> {
        val count = font.getShort(4).toInt() and 0xFFFF
        return (0 until count).associate { i ->
            val record = 12 + i * 16
            val tag = String(ByteArray(4) { font.get(record + it) }, Charsets.US_ASCII)
            tag to font.getInt(record + 8)
        }
    }

    private fun axisTags(font: ByteBuffer, fvar: Int): Set<String> {
        val axesOffset = font.getShort(fvar + 4).toInt() and 0xFFFF
        val axisCount = font.getShort(fvar + 8).toInt() and 0xFFFF
        val axisSize = font.getShort(fvar + 10).toInt() and 0xFFFF
        return (0 until axisCount).map { i ->
            val axis = fvar + axesOffset + i * axisSize
            String(ByteArray(4) { font.get(axis + it) }, Charsets.US_ASCII)
        }.toSet()
    }
}
