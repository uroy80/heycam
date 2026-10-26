package heycam.core.scanner

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.metadata.IIOMetadataNode

/** JPEGs with hand-made metadata segments, built on the JDK's encoder. */
object TestJpegs {
    init {
        System.setProperty("java.awt.headless", "true")
    }

    fun plain(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color.DARK_GRAY
        g.fillRect(width / 8, height / 8, width / 2, height / 16)
        g.dispose()
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpeg", it)) }.toByteArray()
    }

    /** Random pixels, so the compressed data is dense and holds many stuffed 0xFF bytes. */
    fun noise(width: Int, height: Int, seed: Long = 7): BufferedImage {
        val random = Random(seed)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, random.nextInt(0x1000000))
        }
    }

    /**
     * [image] as a JPEG from the JDK's encoder: [progressive] writes several scans, [restartInterval] (in MCUs)
     * puts restart markers into the compressed data.
     */
    fun encode(image: BufferedImage, progressive: Boolean = false, restartInterval: Int = 0): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param = writer.defaultWriteParam
            if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
            val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param)
            if (restartInterval > 0) {
                val format = "javax_imageio_jpeg_image_1.0"
                val tree = metadata.getAsTree(format) as IIOMetadataNode
                val sequence = tree.getElementsByTagName("markerSequence").item(0) as IIOMetadataNode
                sequence.insertBefore(IIOMetadataNode("dri").apply { setAttribute("interval", "$restartInterval") }, sequence.firstChild)
                metadata.setFromTree(format, tree)
            }
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, metadata), param)
            }
            return out.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    /** Every pixel of [jpeg] as the JDK decodes it. */
    fun pixels(jpeg: ByteArray): IntArray {
        val image = checkNotNull(ImageIO.read(jpeg.inputStream())) { "not a decodable JPEG" }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    /** Where each marker [code] starts. Compressed data never holds 0xFF followed by a marker code, so a byte search finds them. */
    fun markerOffsets(jpeg: ByteArray, code: Int): List<Int> =
        (0 until jpeg.size - 1).filter { jpeg[it] == 0xFF.toByte() && jpeg[it + 1] == code.toByte() }

    /** [jpeg] with [segments] inserted at [offset]. */
    fun insertAt(jpeg: ByteArray, offset: Int, vararg segments: ByteArray): ByteArray =
        jpeg.copyOfRange(0, offset) + segments.fold(ByteArray(0)) { acc, s -> acc + s } + jpeg.copyOfRange(offset, jpeg.size)

    /** Hands out one byte per read, so every buffer boundary in the reader gets crossed. */
    class OneByteAtATime(input: InputStream) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else super.read(b, off, 1)
    }

    /** [jpeg] with [segments] inserted right after SOI. */
    fun withSegments(jpeg: ByteArray, vararg segments: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + segments.fold(ByteArray(0)) { acc, s -> acc + s } + jpeg.copyOfRange(2, jpeg.size)

    fun segment(marker: Int, body: ByteArray): ByteArray {
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + body
    }

    /** An EXIF APP1 with an orientation tag and a GPS IFD pointer, in [bigEndian] or little-endian TIFF order. */
    fun exif(orientation: Int, bigEndian: Boolean = true): ByteArray {
        fun u16(v: Int) = if (bigEndian) byteArrayOf((v ushr 8).toByte(), v.toByte()) else byteArrayOf(v.toByte(), (v ushr 8).toByte())
        fun u32(v: Int) = if (bigEndian) u16(v ushr 16) + u16(v and 0xFFFF) else u16(v and 0xFFFF) + u16(v ushr 16)
        val tiff = (if (bigEndian) "MM" else "II").toByteArray() + u16(42) + u32(8) +
            u16(2) + // two entries
            u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) + // Orientation, SHORT
            u16(0x8825) + u16(4) + u32(1) + u32(38) + // GPS IFD pointer
            u32(0) + // no next IFD
            u16(1) + u16(0x0002) + u16(5) + u32(3) + u32(0) + u32(0) // GPS latitude stub
        return segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff)
    }

    fun markers(jpeg: ByteArray): List<Int> {
        val found = ArrayList<Int>()
        var i = 2
        while (i + 4 <= jpeg.size) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            found += marker
            if (marker == 0xDA) break
            i += 2 + (((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF))
        }
        return found
    }

    /** Everything from the start-of-scan marker on: the compressed image itself. */
    fun scanData(jpeg: ByteArray): ByteArray {
        var i = 2
        while (true) {
            if (jpeg[i + 1].toInt() and 0xFF == 0xDA) return jpeg.copyOfRange(i, jpeg.size)
            i += 2 + (((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF))
        }
    }
}
