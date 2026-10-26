package heycam.core.data

import com.google.common.truth.Truth.assertThat
import heycam.core.model.ImageInfo
import heycam.core.model.ImportTarget
import heycam.core.model.Settings
import heycam.core.model.StagedPage
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiskFileStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var rig: DataTestRig
    private val files get() = rig.files
    private val old get() = rig.clock.now.minus(Duration.ofHours(25)).toEpochMilli()

    @Before fun setUp() { rig = DataTestRig(temp.root) }
    @After fun tearDown() = rig.close()

    private fun File.write(bytes: Int, modified: Long? = null): File {
        parentFile?.mkdirs()
        writeBytes(ByteArray(bytes))
        if (modified != null) setLastModified(modified)
        return this
    }

    private val sessions by lazy {
        val settings = object : SettingsRepository {
            override val settings = MutableStateFlow(Settings())
            override suspend fun update(transform: (Settings) -> Settings) = settings.update(transform)
        }
        FileScanSessionStore(rig.dirs, settings, rig.lock, rig.clock, TestDispatchers, rig.log)
    }

    @Test
    fun stagedFilesAreFreshNamesInLooseStaging() {
        val a = files.newStagedFile("JPG")
        val b = files.newStagedFile(".jpg")
        assertThat(a.parentFile).isEqualTo(rig.dirs.looseStaging)
        assertThat(a.name).endsWith(".jpg")
        assertThat(b.name).endsWith(".jpg")
        assertThat(a).isNotEqualTo(b)
        assertThat(rig.dirs.looseStaging.isDirectory).isTrue()
        assertThat(runCatching { files.newStagedFile("../x") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun pageDirsOnlyForFileKeys() {
        val key = "123e4567-e89b-12d3-a456-426614174000"
        assertThat(files.pageDir(key)).isEqualTo(File(rig.dirs.pages, key))
        for (bad in listOf("..", "../files", "x/y", "")) {
            assertThat(runCatching { files.pageDir(bad) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(files.exportDir()).isEqualTo(rig.dirs.exports)
        assertThat(rig.dirs.exports.isDirectory).isTrue()
    }

    @Test
    fun sweepRemovesOrphansStaleStagingOldExportsAndScannerLeftovers() = runTest {
        val id = rig.documents.createDocument("Doc", null, listOf(rig.staged(bytes = 100)))
        val page = rig.documents.observeDocument(id).first()!!.pages.single()
        val pageDir = files.pageDir(page.fileKey)
        val strayOld = File(pageDir, ".render.jpg.tmp").write(7, old)
        val strayNew = File(pageDir, "render-3.jpg").write(5)
        File(pageDir, "source.jpg").setLastModified(old)
        val orphan = File(rig.dirs.pages, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").also { File(it, "source.jpg").write(11) }

        val staleSession = sessions.create(ImportTarget.NewDocument(), listOf(rig.staged(bytes = 13).image))
        File(rig.dirs.staging, staleSession.id).walkBottomUp().forEach { it.setLastModified(old) }
        val liveSession = sessions.create(ImportTarget.NewDocument(), listOf(rig.staged(bytes = 17).image))
        val addedToLive = rig.staged(bytes = 19).image.file.apply { setLastModified(old) }
        sessions.update(liveSession.copy(pages = liveSession.pages + StagedPage(liveSession.pages.single().image.copy(file = addedToLive))))
        val looseOld = rig.staged(bytes = 23).image.file.apply { setLastModified(old) }
        val looseNew = rig.staged(bytes = 29).image.file

        val exportOld = File(rig.dirs.exports, "a1/Doc.pdf").write(31, old).also { it.parentFile!!.setLastModified(old) }
        val exportNew = File(rig.dirs.exports, "b2/Doc.pdf").write(37)
        val mlkitOld = File(rig.dirs.mlkitCache, "page_1.jpg").write(41, old)
        val mlkitNew = File(rig.dirs.mlkitCache, "page_2.jpg").write(43)

        val report = files.sweep()

        assertThat(strayOld.exists()).isFalse()
        assertThat(strayNew.exists()).isTrue()
        assertThat(File(pageDir, "source.jpg").exists()).isTrue()
        assertThat(orphan.exists()).isFalse()
        assertThat(File(rig.dirs.staging, staleSession.id).exists()).isFalse()
        assertThat(File(rig.dirs.staging, liveSession.id).exists()).isTrue()
        assertThat(addedToLive.exists()).isTrue()
        assertThat(looseOld.exists()).isFalse()
        assertThat(looseNew.exists()).isTrue()
        assertThat(exportOld.exists()).isFalse()
        assertThat(exportNew.exists()).isTrue()
        assertThat(mlkitOld.exists()).isFalse()
        assertThat(mlkitNew.exists()).isTrue()
        // strayOld 7 + orphan 11 + stale session (13 + its session.json) + looseOld 23 + exportOld 31 + mlkitOld 41
        val sessionJson = report.bytesFreed - (7 + 11 + 13 + 23 + 31 + 41)
        assertThat(sessionJson).isGreaterThan(0L)
        assertThat(report.filesDeleted).isEqualTo(7)

        assertThat(files.sweep()).isEqualTo(SweepReport(0, 0))
    }

    @Test
    fun clearTemporaryFilesEmptiesExportsAndScannerFilesButKeepsSessions() = runTest {
        val session = sessions.create(ImportTarget.NewDocument(), listOf(rig.staged(bytes = 10).image))
        File(rig.dirs.exports, "a1/Doc.pdf").write(31)
        File(rig.dirs.mlkitCache, "page_1.jpg").write(41)
        val report = files.clearTemporaryFiles()
        assertThat(report).isEqualTo(SweepReport(filesDeleted = 2, bytesFreed = 72))
        assertThat(rig.dirs.exports.list().orEmpty()).isEmpty()
        assertThat(rig.dirs.mlkitCache.list().orEmpty()).isEmpty()
        assertThat(sessions.get(session.id)).isNotNull()
    }

    @Test
    fun usageSplitsLibraryTrashAndCache() = runTest {
        val kept = rig.documents.createDocument("Kept", null, listOf(rig.staged(bytes = 100), rig.staged(bytes = 200)))
        val binned = rig.documents.createDocument("Binned", null, listOf(rig.staged(bytes = 400)))
        rig.documents.trash(listOf(binned))
        val page = rig.documents.observeDocument(kept).first()!!.pages.first()
        val render = File(files.pageDir(page.fileKey), "render-0.jpg").write(50)
        val thumb = File(files.pageDir(page.fileKey), "thumb-0.jpg").write(5)
        rig.documents.saveRender(page.id, 0, ImageInfo(render.path, 1, 1, 50, "image/jpeg"), thumb.path)
        rig.staged(bytes = 1000)
        File(rig.dirs.exports, "x/y.pdf").write(2000)
        File(rig.dirs.mlkitCache, "z.jpg").write(4000)

        assertThat(files.usage()).isEqualTo(StorageUsage(pagesBytes = 355, trashBytes = 400, cacheBytes = 7000))
    }

    @Test
    fun sweepNeverRemovesPagesBeingCommitted() = runTest {
        val staged = List(20) { rig.staged() }
        val commits = staged.mapIndexed { i, page -> async(Dispatchers.IO) { rig.documents.createDocument("Doc $i", null, listOf(page)) } }
        val sweeps = List(20) { async(Dispatchers.IO) { files.sweep() } }
        val ids = commits.awaitAll()
        sweeps.awaitAll()
        for (id in ids) {
            val page = rig.documents.observeDocument(id).first()!!.pages.single()
            assertThat(File(page.source.path).isFile).isTrue()
        }
    }
}
