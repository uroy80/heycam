package heycam.core.pdf

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/** What a PDF image dictionary needs to know about a JPEG that is embedded byte for byte (DCTDecode). */
internal data class JpegInfo(
    val width: Int,
    val height: Int,
    val components: Int,
    /** Adobe APP14 marker on a 4-component image: CMYK stored inverted, which needs a Decode array. */
    val adobeCmyk: Boolean,
)

internal object Jpeg {
    /** Reads the frame header without decoding pixels. Throws [IOException] for anything PDF cannot embed as-is. */
    fun read(file: File): JpegInfo = file.inputStream().buffered().use { read(it) }

    fun read(stream: InputStream): JpegInfo {
        val input = DataInputStream(stream)
        try {
            if (input.readUnsignedShort() != 0xFFD8) throw IOException("Not a JPEG")
            var adobe = false
            while (true) {
                var marker = input.readUnsignedByte()
                if (marker != 0xFF) throw IOException("Corrupt JPEG")
                while (marker == 0xFF) marker = input.readUnsignedByte() // fill bytes
                when (marker) {
                    0x01, in 0xD0..0xD7 -> continue // standalone markers carry no length
                    0xDA, 0xD9 -> throw IOException("JPEG has no frame header")
                }
                val length = input.readUnsignedShort() - 2
                if (length < 0) throw IOException("Corrupt JPEG")
                if (isStartOfFrame(marker)) {
                    val precision = input.readUnsignedByte()
                    val height = input.readUnsignedShort()
                    val width = input.readUnsignedShort()
                    val components = input.readUnsignedByte()
                    if (precision != 8) throw IOException("Unsupported JPEG precision $precision")
                    if (width == 0 || height == 0) throw IOException("JPEG without a fixed size")
                    if (components != 1 && components != 3 && components != 4) {
                        throw IOException("Unsupported JPEG with $components components")
                    }
                    return JpegInfo(width, height, components, adobeCmyk = adobe && components == 4)
                }
                if (marker == 0xEE && length >= 5) {
                    val tag = ByteArray(5).also { input.readFully(it) }
                    adobe = adobe || tag.decodeToString() == "Adobe"
                    input.skipFully(length - 5)
                } else {
                    input.skipFully(length)
                }
            }
        } catch (e: EOFException) {
            throw IOException("Truncated JPEG", e)
        }
    }

    /** SOF0–SOF15 except DHT (C4), JPG (C8) and DAC (CC). */
    private fun isStartOfFrame(marker: Int) = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    private fun DataInputStream.skipFully(count: Int) {
        var left = count
        while (left > 0) {
            val skipped = skipBytes(left)
            if (skipped <= 0) {
                readUnsignedByte()
                left--
            } else {
                left -= skipped
            }
        }
    }
}
