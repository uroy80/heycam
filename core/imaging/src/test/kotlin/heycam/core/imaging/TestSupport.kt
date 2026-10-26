package heycam.core.imaging

import android.graphics.Bitmap
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.common.StagingArea
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal object TestDispatchers : DispatcherProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
}

internal class RecordingLog : HeycamLog {
    val lines = mutableListOf<String>()
    override fun d(tag: String, message: String) { lines += "D/$tag: $message" }
    override fun w(tag: String, message: String, error: Throwable?) { lines += "W/$tag: $message" }
    override fun e(tag: String, message: String, error: Throwable?) { lines += "E/$tag: $message" }
}

/** Staging in a temporary folder, laid out like the real FileStore's loose staging. */
internal class TempStaging(root: File) : StagingArea {
    val dir = File(root, "staging/loose")
    override fun newStagedFile(extension: String): File = File(dir.apply { mkdirs() }, "${UUID.randomUUID()}.$extension")
}

internal fun fixture(name: String): File {
    val url = requireNotNull(TestDispatchers::class.java.classLoader?.getResource("fixtures/$name")) { "missing fixture $name" }
    return File(url.toURI())
}

internal fun rgb(r: Int, g: Int, b: Int) = -0x1000000 or (r shl 16) or (g shl 8) or b

/** A bitmap filled with [background] and a [marker]-coloured 50 px square in its top-left corner. */
internal fun markedBitmap(width: Int, height: Int, background: Int = rgb(240, 240, 240), marker: Int = rgb(220, 20, 20)): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(background)
    val block = IntArray(50 * 50) { marker }
    bitmap.setPixels(block, 0, 50, 0, 0, 50, 50)
    return bitmap
}

internal fun Bitmap.writeTo(file: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 95): File {
    file.parentFile?.mkdirs()
    file.outputStream().use { check(compress(format, quality, it)) }
    return file
}

internal fun isReddish(c: Int) = red(c) > 150 && green(c) < 90 && blue(c) < 90

/**
 * A synthetic document page: tinted paper lit from the right (the left edge in shadow), rows of dark "word" bars
 * and a little deterministic sensor noise. [isInk] tells which pixels belong to text.
 */
internal class SyntheticPage(
    val width: Int,
    val height: Int,
    private val paper: Int = rgb(236, 228, 205),
    private val ink: Int = rgb(35, 35, 40),
    private val shadow: Float = 0.45f,
    private val noise: Int = 6,
    private val withText: Boolean = true,
) {
    private val lineHeight = 14
    private val linePitch = 40
    private val margin = 40
    private val words: List<IntRange> = buildList {
        var x = margin
        var seed = 7
        while (true) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val w = 30 + seed % 90
            if (x + w > width - margin) break
            add(x until x + w)
            x += w + 14
        }
    }

    fun isInk(x: Int, y: Int): Boolean {
        if (!withText || y < 60 || y > height - 60) return false
        if ((y - 60) % linePitch >= lineHeight) return false
        return words.any { x in it }
    }

    fun pixels(): IntArray {
        val px = IntArray(width * height)
        var seed = 12345
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = if (isInk(x, y)) ink else paper
                val light = 1f - shadow * (1f - x.toFloat() / (width - 1))
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val n = if (noise == 0) 0 else seed % (2 * noise + 1) - noise
                px[y * width + x] = rgb(
                    (red(base) * light + n).toInt().coerceIn(0, 255),
                    (green(base) * light + n).toInt().coerceIn(0, 255),
                    (blue(base) * light + n).toInt().coerceIn(0, 255),
                )
            }
        }
        return px
    }
}
