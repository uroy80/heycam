package heycam.core.designsystem

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Play listing in store/ (owned with the design system): Play's length limits, image formats, and the words
 * the metadata policy and our own copy rules keep out. The build passes the folder as `heycam.storeDir`.
 */
class StoreListingTest {
    private val store = File(checkNotNull(System.getProperty("heycam.storeDir")) { "heycam.storeDir is not set" })
    private val listing = store.resolve("listings/en-US")

    private fun text(path: String) = listing.resolve(path).readText()

    @Test
    fun textFitsPlayLimits() {
        assertThat(text("title.txt")).isEqualTo("heycam: Document Scanner")
        assertThat(text("short-description.txt").length).isAtMost(80)
        assertThat(text("full-description.txt").length).isAtMost(4000)
        assertThat(store.resolve("whatsnew/whatsnew-en-US").readText().trim().length).isAtMost(500)
        listOf("title.txt", "short-description.txt").forEach {
            assertWithMessage(it).that(text(it)).doesNotContain("\n")
        }
    }

    @Test
    fun copyAvoidsClaimsPlayAndOwnerRulesForbid() {
        val banned = listOf(
            "best", "#1", "number one", "top", "free", "new", "100%", "no data", "unlimited",
            "adobe", "camscanner", "microsoft lens", "genius scan", "drive", "dropbox", "100+ languages",
        )
        val files = listOf("title.txt", "short-description.txt", "full-description.txt").map { text(it) } +
            store.resolve("whatsnew/whatsnew-en-US").readText()
        files.forEach { copy ->
            val words = copy.lowercase()
            banned.forEach { term ->
                val pattern = Regex("(^|[^a-z0-9])${Regex.escape(term)}($|[^a-z0-9])")
                assertWithMessage("listing says \"$term\"").that(pattern.containsMatchIn(words)).isFalse()
            }
        }
    }

    /**
     * Features ML Kit's scanner does not document and no device run has shown. Its full mode erases stains and
     * fingers; shadow removal is not on Google's list. Take a word off only with a device check recorded.
     */
    @Test
    fun copyClaimsOnlyWhatTheScannerDoes() {
        val full = text("full-description.txt").lowercase()
        listOf("shadow", "glare").forEach { claim ->
            assertWithMessage("listing claims \"$claim\"").that(full).doesNotContain(claim)
        }
        assertThat(full).contains("stains and fingers")
    }

    @Test
    fun listingStatesTheHonestLimits() {
        val full = text("full-description.txt")
        assertThat(full).contains("Google Play services")
        assertThat(full).contains("2 GB of RAM")
        assertThat(full).contains("Google ML Kit")
        listOf("Latin", "Devanagari", "Chinese", "Japanese", "Korean").forEach { assertThat(full).contains(it) }
    }

    @Test
    fun iconIs512SquarePngWithAlpha() {
        val file = listing.resolve("graphics/icon/icon.png")
        val image = checkNotNull(ImageIO.read(file))
        assertThat(image.width).isEqualTo(512)
        assertThat(image.height).isEqualTo(512)
        assertThat(image.colorModel.hasAlpha()).isTrue()
        assertThat(file.length()).isAtMost(1024L * 1024)
    }

    @Test
    fun featureGraphicIs1024By500WithoutAlpha() {
        val file = listing.resolve("graphics/feature-graphic/feature-graphic.png")
        val image = checkNotNull(ImageIO.read(file))
        assertThat(image.width).isEqualTo(1024)
        assertThat(image.height).isEqualTo(500)
        assertThat(image.colorModel.hasAlpha()).isFalse()
        assertThat(file.length()).isAtMost(15L * 1024 * 1024)
    }

    /**
     * Play wants 2 to 8 phone screenshots, each side 320 to 3840 px and the long side at most twice the short one.
     * Ours are 1080 x 1920 (9:16), 24-bit without alpha, named `1-home.png` to `n-….png` in upload order. Skipped
     * until the folder exists; see store/README.md for how they are captured.
     */
    @Test
    fun phoneScreenshotsAre1080By1920WithoutAlpha() {
        val dir = listing.resolve("graphics/phone-screenshots")
        assumeTrue("phone screenshots not captured yet", dir.isDirectory)
        val shots = checkNotNull(dir.listFiles()).filterNot { it.isHidden }.sortedBy { it.name }
        assertThat(shots.size).isAtLeast(2)
        assertThat(shots.size).isAtMost(8)
        assertThat(shots.map { it.name.substringBefore('-') }).isEqualTo((1..shots.size).map { "$it" })
        shots.forEach { file ->
            assertWithMessage(file.name).that(file.name).matches("[1-8]-[a-z0-9-]+\\.png")
            val image = checkNotNull(ImageIO.read(file)) { "${file.name} is not a PNG" }
            assertWithMessage("${file.name} width").that(image.width).isEqualTo(1080)
            assertWithMessage("${file.name} height").that(image.height).isEqualTo(1920)
            assertWithMessage("${file.name} has alpha").that(image.colorModel.hasAlpha()).isFalse()
            assertWithMessage("${file.name} bytes").that(file.length()).isAtMost(8L * 1024 * 1024)
        }
    }
}
