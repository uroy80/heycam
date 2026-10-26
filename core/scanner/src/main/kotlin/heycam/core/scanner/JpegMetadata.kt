package heycam.core.scanner

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Lossless metadata removal for the scanner's JPEGs (PLAN P4: stored images carry no EXIF or GPS).
 *
 * Segments are copied as they are, without decoding, except: APP1 (EXIF, XMP), APP3–APP13 (IPTC and other
 * maker data), APP15, comments, and APP2 segments that are not an ICC profile. JFIF (APP0), the colour profile and
 * Adobe's colour-transform marker (APP14) stay, because decoders need them to show the right colours.
 *
 * The copy ends at the end-of-image marker. Whatever a camera appends after it (a motion photo's video, MPF's
 * extra images, maker trailers) is left behind. Segments between the scans of a progressive JPEG are filtered too.
 */
internal object JpegMetadata {
    data class Info(val width: Int, val height: Int, val orientation: Int)

    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val TEM = 0x01
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE
    private const val COM = 0xFE
    private const val TAG_ORIENTATION = 0x0112
    private val ICC = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
    private val EXIF = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)

    /** Copies [input] to [output] without metadata and returns the frame size and the EXIF orientation (1 = upright). */
    fun strip(input: InputStream, output: OutputStream): Info {
        val data = Reader(input)
        try {
            if (data.u8() != 0xFF || data.u8() != SOI) throw IOException("Not a JPEG")
            output.write(0xFF)
            output.write(SOI)
            var width = 0
            var height = 0
            var orientation = 1
            var scanned = false
            var marker = data.marker()
            while (true) {
                if (marker == EOI) {
                    if (!scanned) throw IOException("JPEG has no image data")
                    output.write(0xFF)
                    output.write(EOI)
                    return Info(width, height, orientation)
                }
                if (marker == TEM || marker in 0xD0..0xD7) {
                    output.write(0xFF)
                    output.write(marker)
                    marker = data.marker()
                    continue
                }
                val length = data.u16()
                if (length < 2) throw IOException("Corrupt JPEG")
                val body = data.bytes(length - 2)
                if (isStartOfFrame(marker) && body.size >= 5) {
                    height = u16(body, 1)
                    width = u16(body, 3)
                }
                if (marker == APP1 && body.startsWith(EXIF)) orientation = exifOrientation(body, EXIF.size) ?: orientation
                if (keep(marker, body)) {
                    output.write(0xFF)
                    output.write(marker)
                    output.write(length ushr 8)
                    output.write(length and 0xFF)
                    output.write(body)
                }
                if (marker != SOS) {
                    marker = data.marker()
                    continue
                }
                if (width <= 0 || height <= 0) throw IOException("JPEG without a frame size")
                scanned = true
                marker = data.copyScan(output)
                if (marker < 0) {
                    // The file ends inside the image data: close it the way a decoder would read it.
                    output.write(0xFF)
                    output.write(EOI)
                    return Info(width, height, orientation)
                }
            }
        } catch (e: EOFException) {
            throw IOException("Truncated JPEG", e)
        }
    }

    private fun keep(marker: Int, body: ByteArray): Boolean = when {
        marker == COM -> false
        marker == APP2 -> body.startsWith(ICC)
        marker == APP14 -> true
        marker in APP1..0xEF -> false
        else -> true
    }

    /** SOF0–SOF15 except DHT (C4), JPG (C8) and DAC (CC). */
    private fun isStartOfFrame(marker: Int) = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    /** The orientation tag (1–8) of IFD0 in a TIFF structure starting at [start], or null when there is none. */
    private fun exifOrientation(body: ByteArray, start: Int): Int? {
        if (body.size < start + 8) return null
        val little = when (String(body, start, 2, Charsets.US_ASCII)) {
            "II" -> true
            "MM" -> false
            else -> return null
        }
        fun u16(at: Int): Int = if (little) {
            (body[at].toInt() and 0xFF) or ((body[at + 1].toInt() and 0xFF) shl 8)
        } else {
            ((body[at].toInt() and 0xFF) shl 8) or (body[at + 1].toInt() and 0xFF)
        }
        fun u32(at: Int): Long = if (little) {
            (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }
        val ifd = start + u32(start + 4)
        if (ifd < start || ifd + 2 > body.size) return null
        val entries = u16(ifd.toInt())
        for (i in 0 until entries) {
            val entry = ifd.toInt() + 2 + 12 * i
            if (entry + 12 > body.size) return null
            if (u16(entry) == TAG_ORIENTATION) return u16(entry + 8).takeIf { it in 1..8 }
        }
        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    /** A buffered reader that copies entropy-coded data in runs rather than byte by byte. */
    private class Reader(private val input: InputStream) {
        private val buffer = ByteArray(64 * 1024)
        private var pos = 0
        private var end = 0

        private fun fill(): Boolean {
            if (pos < end) return true
            pos = 0
            end = input.read(buffer).coerceAtLeast(0)
            return end > 0
        }

        fun u8(): Int {
            if (!fill()) throw EOFException()
            return buffer[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun bytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var done = 0
            while (done < count) {
                if (!fill()) throw EOFException()
                val chunk = minOf(count - done, end - pos)
                System.arraycopy(buffer, pos, out, done, chunk)
                pos += chunk
                done += chunk
            }
            return out
        }

        /** The next marker code, after its 0xFF and any fill bytes. */
        fun marker(): Int {
            if (u8() != 0xFF) throw IOException("Corrupt JPEG")
            var marker = u8()
            while (marker == 0xFF) marker = u8()
            return marker
        }

        /**
         * Copies one scan's entropy-coded data, with its stuffed zero bytes and restart markers, and returns the
         * marker that ends it, or -1 when the input ends first.
         */
        fun copyScan(output: OutputStream): Int {
            while (true) {
                if (!fill()) return -1
                var run = pos
                while (run < end && buffer[run] != FF) run++
                output.write(buffer, pos, run - pos)
                pos = run
                if (pos == end) continue
                pos++ // the 0xFF
                var next: Int
                do {
                    if (!fill()) return -1
                    next = buffer[pos++].toInt() and 0xFF
                } while (next == 0xFF) // fill bytes before a marker
                if (next != 0x00 && next !in 0xD0..0xD7) return next
                output.write(0xFF)
                output.write(next)
            }
        }

        private companion object {
            const val FF: Byte = -1 // 0xFF
        }
    }
}
