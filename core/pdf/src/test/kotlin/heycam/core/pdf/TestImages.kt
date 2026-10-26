package heycam.core.pdf

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Real JPEG files for the writer tests, made with the JDK's encoder so no Android graphics are involved. */
object TestImages {
    init {
        System.setProperty("java.awt.headless", "true")
    }

    fun jpeg(file: File, width: Int, height: Int, gray: Boolean = false): File {
        val type = if (gray) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color(40, 60, 90)
        g.fillRect(width / 10, height / 10, width / 3, height / 20)
        g.dispose()
        check(ImageIO.write(image, "jpeg", file)) { "no JPEG writer" }
        return file
    }

    fun png(file: File, width: Int, height: Int): File {
        check(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file))
        return file
    }
}
