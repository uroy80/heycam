package heycam.feature.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import heycam.core.model.Page
import heycam.core.model.PageId
import heycam.feature.document.text.findMatches
import heycam.feature.document.viewer.PageViewerScreen
import heycam.feature.document.viewer.ViewerCallbacks
import heycam.feature.document.viewer.ViewerUiState
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens for the document screens. Record: gw :feature:document:recordRoborazziDebug; CI verifies with
 * verifyRoborazziDebug. Dates are formatted in UTC so the goldens do not depend on the machine's time zone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DocumentScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val zone = TimeZone.getDefault()

    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = TimeZone.setDefault(zone)

    private val texts = mapOf(
        PageId(1) to pageText(1, "TENANCY AGREEMENT\n\nThe tenant will pay a deposit of four weeks' rent before moving in. The deposit is held in a protection scheme."),
        PageId(2) to pageText(2, "Rent is due on the first day of each month by bank transfer."),
        PageId(3) to pageText(3, "Signed by both parties on 1 October 2026."),
    )

    /**
X     * happens on a background thread, which Robolectric's native graphics cannot do.)
     */
    private fun scannedPages(): List<Page> = (1..3).map { n -> page(n.toLong(), n - 1).copy(thumbPath = "/missing/p$n.jpg") }

    private fun captureDocument(name: String, tab: DocumentTab, query: String = "", fontScale: Float = 1f) {
        val pages = scannedPages()
        val state = DocumentUiState(loaded = true, document = document(pages = pages), pages = pages, texts = texts)
        val matches = findMatches(pages.map { it.id to texts[it.id]?.text }, query)
        val find = FindState(query, matches, current = if (matches.size > 1) 1 else 0)
        rule.setContent {
            HeycamTheme(ThemeMode.DayPaper) {
                AtFontScale(fontScale) { DocumentScreen(state, tab, find, initialPage = null, snackbar = SnackbarHostState(), callbacks = DocumentCallbacks()) }
            }
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun pagesTab() = captureDocument("document_pages_day", DocumentTab.PAGES)

    /** Two matches: the current one in reverse, the other in the selection colour and underlined. */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun textWithFind() = captureDocument("document_text_find_day", DocumentTab.TEXT, query = "deposit")

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun pagesAtDoubleFontSize() = captureDocument("document_pages_font200", DocumentTab.PAGES, fontScale = 2f)

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun textAtDoubleFontSize() = captureDocument("document_text_font200", DocumentTab.TEXT, fontScale = 2f)

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xhdpi")
    fun textInLandscape() = captureDocument("document_text_landscape", DocumentTab.TEXT)

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xhdpi")
    fun pagesInLandscape() = captureDocument("document_pages_landscape", DocumentTab.PAGES)

    /** A small 300 x 200 import fills the viewer instead of sitting at its pixel size. */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun viewerFitsASmallPhoto() {
        val pages = listOf(page(1, 0), page(2, 1))
        val picture = pagePicture(300, 200)
        val state = ViewerUiState(loaded = true, document = document(pages = pages), pages = pages, current = 0)
        rule.setContent {
            HeycamTheme(ThemeMode.DayPaper) { PageViewerScreen(state, { _, _, _ -> picture }, SnackbarHostState(), ViewerCallbacks()) }
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/viewer_small_photo.png")
    }
}

/** A page-like picture: paper with a title bar and ruled lines, [w] x [h] pixels. */
fun pagePicture(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
    eraseColor(0xFFFBF8F1.toInt())
    val canvas = Canvas(this)
    val ink = Paint().apply { color = 0xFF141210.toInt() }
    canvas.drawRect(w * 0.1f, h * 0.08f, w * 0.7f, h * 0.14f, ink)
    var y = h * 0.24f
    while (y < h * 0.9f) {
        canvas.drawRect(w * 0.1f, y, w * 0.9f, y + maxOf(1f, h * 0.01f), ink)
        y += h * 0.08f
    }
}
