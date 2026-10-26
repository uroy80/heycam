package heycam.core.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.data.db.HeycamDatabase
import heycam.core.model.BoxPx
import heycam.core.model.ImageInfo
import heycam.core.model.OcrScript
import heycam.core.model.PageOrigin
import heycam.core.model.PageText
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import heycam.core.model.TextLine
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN 6.4: FTS5 trigram search on the device's bundled SQLite (the JVM tests use the host build of the same
 * library): Latin, Devanagari and CJK, and the index following text edits and deletes.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSearchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "device-search-test").apply { deleteRecursively() }
    private val dirs = StorageDirs(File(root, "files"), File(root, "cache"))
    private val clock = Clock { Instant.now() }
    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.Main
    }
    private val log = object : HeycamLog {
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, error: Throwable?) = Unit
        override fun e(tag: String, message: String, error: Throwable?) = Unit
    }
    private val db = Room.inMemoryDatabaseBuilder<HeycamDatabase>(context).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private val lock = StorageLock()
    private val files = DiskFileStore(dirs, db, lock, clock, dispatchers, log)
    private val repo = RoomDocumentRepository(db, files, lock, clock, dispatchers, log)

    @After fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private suspend fun document(title: String, text: String): heycam.core.model.DocumentId {
        val staged = files.newStagedFile("jpg").apply { writeBytes(ByteArray(16)) }
        val id = repo.createDocument(title, null, listOf(StagedPage(StagedImage(staged, 10, 10, "image/jpeg", PageOrigin.SCANNER))))
        val page = repo.observeDocument(id).first()!!.pages.single()
        val thumb = File(files.pageDir(page.fileKey), "thumb.jpg").apply { writeBytes(ByteArray(1)) }
        repo.saveRender(page.id, 0, ImageInfo(page.source.path, 10, 10, 16, "image/jpeg"), thumb.path)
        repo.saveText(PageText(page.id, OcrScript.LATIN, text, listOf(TextLine(text, BoxPx(0f, 0f, 1f, 1f))), 10, 10, 0, "device-test", Instant.now()))
        return id
    }

    private suspend fun found(query: String) = repo.search(SearchQuery(query)).filterIsInstance<SearchHit.TextHit>().map { it.document.id }

    @Test
    fun trigramSearchInEveryScriptAndIndexSync() = runBlocking {
        val latin = document("A", "HEYCAM SPIKE TEST INVOICE")
        val hindi = document("B", "नमस्ते, यह एक परीक्षण दस्तावेज़ है। धन्यवाद")
        val cjk = document("C", "東京駅で会う 안녕하세요")
        assertThat(found("invoice")).containsExactly(latin)
        assertThat(found("धन्यवाद")).containsExactly(hindi)
        assertThat(found("東京駅")).containsExactly(cjk)
        assertThat(found("会う")).containsExactly(cjk)
        assertThat(found("안녕하")).containsExactly(cjk)

        val page = repo.observeDocument(latin).first()!!.pages.single()
        repo.updateText(page.id, "Receipt")
        assertThat(found("invoice")).isEmpty()
        assertThat(found("receipt")).containsExactly(latin)
        repo.deleteForever(listOf(latin))
        assertThat(found("receipt")).isEmpty()
    }
}
