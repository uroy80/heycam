package heycam.core.data

import com.google.common.truth.Truth.assertThat
import heycam.core.model.BoxPx
import heycam.core.model.DocumentId
import heycam.core.model.FolderId
import heycam.core.model.ImageInfo
import heycam.core.model.OcrScript
import heycam.core.model.PageText
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.core.model.TextLine
import java.io.File
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Search over titles and recognised text on the bundled SQLite (FTS5 trigram), the same engine the app ships. */
@RunWith(RobolectricTestRunner::class)
class SearchTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var rig: DataTestRig
    private val repo get() = rig.documents

    @Before fun setUp() { rig = DataTestRig(temp.root) }
    @After fun tearDown() = rig.close()

    /** Creates a document whose pages carry [texts] as recognised text. */
    private suspend fun doc(title: String, vararg texts: String, folder: FolderId? = null): DocumentId {
        val id = repo.createDocument(title, folder, texts.map { rig.staged() })
        rig.clock.advance(Duration.ofMinutes(1))
        for ((page, text) in repo.observeDocument(id).first()!!.pages.zip(texts)) {
            val dir = rig.files.pageDir(page.fileKey)
            val thumb = File(dir, "thumb.jpg").apply { writeBytes(ByteArray(1)) }
            repo.saveRender(page.id, page.editsVersion, ImageInfo(page.source.path, 100, 150, 1, "image/jpeg"), thumb.path)
            val lines = text.split("\n").map { TextLine(it, BoxPx(0f, 0f, 10f, 10f)) }
            repo.saveText(PageText(page.id, OcrScript.LATIN, text, lines, 100, 150, page.editsVersion, "test", rig.clock.now))
        }
        return id
    }

    private fun List<SearchHit>.textHits() = filterIsInstance<SearchHit.TextHit>()
    private fun List<SearchHit>.titleHits() = filterIsInstance<SearchHit.TitleHit>()

    @Test
    fun findsSubstringsInEveryScript() = runTest {
        val invoice = doc("Letter", "HEYCAM SPIKE TEST INVOICE\nTotal amount due: Rs 4,250.00")
        val hindi = doc("Hindi", "नमस्ते, यह एक परीक्षण दस्तावेज़ है।\nधन्यवाद")
        val japanese = doc("Japanese", "明日、東京駅で会う")
        val korean = doc("Korean", "안녕하세요 반갑습니다")

        assertThat(repo.search(SearchQuery("voice")).textHits().map { it.document.id }).containsExactly(invoice)
        assertThat(repo.search(SearchQuery("AMOUNT DUE")).textHits().map { it.document.id }).containsExactly(invoice)
        assertThat(repo.search(SearchQuery("धन्यवाद")).textHits().map { it.document.id }).containsExactly(hindi)
        assertThat(repo.search(SearchQuery("東京駅")).textHits().map { it.document.id }).containsExactly(japanese)
        assertThat(repo.search(SearchQuery("반갑습")).textHits().map { it.document.id }).containsExactly(korean)
    }

    @Test
    fun shortQueriesScanTheText() = runTest {
        val japanese = doc("Japanese", "東京駅で会う")
        doc("Other", "nothing here")
        assertThat(repo.search(SearchQuery("会う")).textHits().map { it.document.id }).containsExactly(japanese)
        assertThat(repo.search(SearchQuery("駅")).textHits().map { it.document.id }).containsExactly(japanese)
        assertThat(repo.search(SearchQuery("No")).textHits().map { it.document.id }).hasSize(1)
    }

    @Test
    fun queryAndTextAreComparedInNfc() = runTest {
        val id = doc("Menu", "Café au lait")
        assertThat(repo.search(SearchQuery("Café")).textHits().map { it.document.id }).containsExactly(id)
        assertThat(repo.search(SearchQuery("Café")).textHits().map { it.document.id }).containsExactly(id)
    }

    @Test
    fun quotesAndOperatorsAreLiteral() = runTest {
        val id = doc("Quote", "He said \"OR NOT\" AND left")
        assertThat(repo.search(SearchQuery("\"OR NOT\"")).textHits().map { it.document.id }).containsExactly(id)
        assertThat(repo.search(SearchQuery("AND left")).textHits().map { it.document.id }).containsExactly(id)
        assertThat(repo.search(SearchQuery("NEAR(")).textHits()).isEmpty()
    }

    @Test
    fun titleHitsComeFirstWithTheMatchRange() = runTest {
        val bill = doc("Electricity bill", "Meter reading")
        val other = doc("Notes", "pay the bill today")
        val hits = repo.search(SearchQuery("BILL"))
        assertThat(hits.first()).isInstanceOf(SearchHit.TitleHit::class.java)
        val title = hits.titleHits().single()
        assertThat(title.document.id).isEqualTo(bill)
        assertThat(title.match).isEqualTo(12..15)
        assertThat(hits.textHits().map { it.document.id }).containsExactly(other)
        assertThat(repo.search(SearchQuery("bill", inText = false)).map { it.document.id }).containsExactly(bill)
    }

    @Test
    fun textHitsCarryPageAndSnippet() = runTest {
        val filler = "word ".repeat(30)
        val id = doc("Report", "first page", "${filler}the Invoice number is 42 $filler")
        val hit = repo.search(SearchQuery("invoice")).textHits().single()
        assertThat(hit.document.id).isEqualTo(id)
        assertThat(hit.pageIndex).isEqualTo(1)
        assertThat(hit.pageId).isEqualTo(repo.observeDocument(id).first()!!.pages[1].id)
        assertThat(hit.snippet.text).startsWith("…")
        assertThat(hit.snippet.text).endsWith("…")
        val match = hit.snippet.matches.single()
        assertThat(hit.snippet.text.substring(match.first, match.last + 1)).isEqualTo("Invoice")
    }

    @Test
    fun onePageOneHitAndTheLimitHolds() = runTest {
        doc("A", "apple apple apple", "apple pie")
        doc("B", "apple")
        assertThat(repo.search(SearchQuery("apple")).textHits()).hasSize(3)
        assertThat(repo.search(SearchQuery("apple", limit = 2)).textHits()).hasSize(2)
        assertThat(repo.search(SearchQuery("apple", limit = 0))).isEmpty()
        assertThat(repo.search(SearchQuery("   "))).isEmpty()
    }

    @Test
    fun folderScopeIncludesSubFoldersAndTrashIsExcluded() = runTest {
        val work = rig.folders.create("Work", null)
        val tax = rig.folders.create("Tax", work)
        val inWork = doc("Receipt one", "receipt", folder = work)
        val inTax = doc("Receipt two", "receipt", folder = tax)
        val atRoot = doc("Receipt three", "receipt")
        val trashed = doc("Receipt four", "receipt", folder = work)
        repo.trash(listOf(trashed))

        val everywhere = repo.search(SearchQuery("receipt"))
        assertThat(everywhere.titleHits().map { it.document.id }).containsExactly(inWork, inTax, atRoot)
        assertThat(everywhere.textHits().map { it.document.id }).containsExactly(inWork, inTax, atRoot)
        val scoped = repo.search(SearchQuery("receipt", folderId = work))
        assertThat(scoped.titleHits().map { it.document.id }).containsExactly(inWork, inTax)
        assertThat(scoped.textHits().map { it.document.id }).containsExactly(inWork, inTax)
        assertThat(repo.search(SearchQuery("re", folderId = tax)).textHits().map { it.document.id }).containsExactly(inTax)
    }

    @Test
    fun indexFollowsTextChangesAndDeletes() = runTest {
        val id = doc("Doc", "alpha bravo", "charlie delta")
        val pages = repo.observeDocument(id).first()!!.pages
        repo.updateText(pages[0].id, "echo foxtrot")
        assertThat(repo.search(SearchQuery("alpha")).textHits()).isEmpty()
        assertThat(repo.search(SearchQuery("foxtrot")).textHits()).hasSize(1)

        repo.deletePages(listOf(pages[1].id))
        assertThat(repo.search(SearchQuery("charlie")).textHits()).isEmpty()

        repo.deleteForever(listOf(id))
        assertThat(repo.search(SearchQuery("foxtrot")).textHits()).isEmpty()
    }
}
