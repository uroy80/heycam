package heycam.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertWithMessage
import heycam.core.model.ImageInfo
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.SizePreset
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Measures the bytes per page of every [SizePreset] x MVP filter on two sample pages, and guards the numbers recorded
 * in docs/export-presets.txt. Run it to re-measure: the table is printed to the test's standard output.
 *
 * Samples: "scan" is a page the ML Kit scanner returned on the emulator (1081 x 1549, already cleaned up by ML Kit);
 * "photo" is that page enlarged to 2480 px wide (A4 at 300 dpi) with tinted paper, uneven light and sensor noise,
 * standing in for a full-resolution phone capture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportPresetMeasurementTest {
    @get:Rule val temp = TemporaryFolder()

    private val renderer = BitmapPageRenderer(DocumentPixelFilters(), TestDispatchers)
    private val filters = listOf(PageFilter.ORIGINAL, PageFilter.AUTO, PageFilter.GRAYSCALE, PageFilter.BLACK_WHITE)

    /** Upper bounds per page, in KB, with headroom over the measured values in docs/export-presets.txt. */
    private val budgetKb = mapOf(
        SizePreset.SMALL to 300L,
        SizePreset.STANDARD to 900L,
        SizePreset.ORIGINAL to 2500L,
    )

    @Test
    fun presetsShrinkInOrderAndStayInBudget() = runTest {
        val samples = mapOf("scan" to info(fixture("scanner_page.jpg")), "photo" to info(photoSample()))
        val rows = mutableListOf<String>()
        for ((name, source) in samples) {
            for (filter in filters) {
                val sizes = SizePreset.entries.associateWith { preset ->
                    val out = File(temp.root, "out-${preset.name}.jpg")
                    renderer.render(source, PageEdits(filter = filter), RenderSpec(preset.maxLongEdgePx, ImageFormat.JPEG, preset.jpegQuality), out).bytes
                }
                rows += "%-6s %-12s %8d %8d %8d".format(name, filter, sizes.getValue(SizePreset.SMALL) / 1024, sizes.getValue(SizePreset.STANDARD) / 1024, sizes.getValue(SizePreset.ORIGINAL) / 1024)
                assertWithMessage("$name $filter: Small < Standard").that(sizes.getValue(SizePreset.SMALL)).isLessThan(sizes.getValue(SizePreset.STANDARD))
                assertWithMessage("$name $filter: Standard < Original").that(sizes.getValue(SizePreset.STANDARD)).isLessThan(sizes.getValue(SizePreset.ORIGINAL))
                for ((preset, bytes) in sizes) {
                    assertWithMessage("$name $filter $preset KB").that(bytes / 1024).isAtMost(budgetKb.getValue(preset))
                }
            }
        }
        println("sample filter        Small Standard Original   (KB per page)")
        rows.forEach(::println)
    }

    private fun info(file: File): ImageInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return ImageInfo(file.path, bounds.outWidth, bounds.outHeight, file.length(), "image/jpeg")
    }

    /** The scan enlarged to A4 at 300 dpi, re-lit and noised like a camera capture, saved as heycam stores imports (q95). */
    private fun photoSample(): File {
        val scan = BitmapFactory.decodeFile(fixture("scanner_page.jpg").path)
        val width = 2480
        val height = scan.height * width / scan.width
        val big = Bitmap.createScaledBitmap(scan, width, height, true)
        scan.recycle()
        val px = IntArray(width * height)
        big.getPixels(px, 0, width, 0, 0, width, height)
        var seed = 99
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val light = 0.78f + 0.2f * x / width + 0.02f * y / height
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val noise = seed % 9 - 4
                val c = px[i]
                px[i] = rgb(
                    (red(c) * light * 0.97f + noise).toInt().coerceIn(0, 255),
                    (green(c) * light * 0.94f + noise).toInt().coerceIn(0, 255),
                    (blue(c) * light * 0.84f + noise).toInt().coerceIn(0, 255),
                )
            }
        }
        big.setPixels(px, 0, width, 0, 0, width, height)
        val file = File(temp.root, "photo.jpg")
        big.writeTo(file, quality = DecodingImageImporter.JPEG_QUALITY)
        big.recycle()
        return file
    }
}
