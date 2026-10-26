package heycam.core.data

import com.google.common.truth.Truth.assertThat
import heycam.core.model.DocumentId
import heycam.core.model.FolderId
import heycam.core.model.ImportTarget
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.Rotation
import heycam.core.model.Settings
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import java.io.File
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
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
class FileScanSessionStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var rig: DataTestRig
    private val settings = object : SettingsRepository {
        val state = MutableStateFlow(Settings(defaultFilter = PageFilter.AUTO))
        override val settings = state
        override suspend fun update(transform: (Settings) -> Settings) = state.update(transform)
    }
    private lateinit var store: FileScanSessionStore

    @Before fun setUp() {
        rig = DataTestRig(temp.root)
        store = newStore()
    }

    @After fun tearDown() = rig.close()

    private fun newStore() = FileScanSessionStore(rig.dirs, settings, rig.lock, rig.clock, TestDispatchers, rig.log)

    private fun images(n: Int): List<StagedImage> = List(n) { rig.staged(width = 100 + it, height = 200).image }

    @Test
    fun createMovesLooseFilesIntoTheSessionAndUsesTheDefaultFilter() = runTest {
        val loose = images(2)
        val session = store.create(ImportTarget.NewDocument(title = "Lease", folderId = FolderId(3)), loose)
        val dir = File(rig.dirs.staging, session.id)
        assertThat(session.pages.map { it.image.file.parentFile }).containsExactly(dir, dir)
        assertThat(session.pages.all { it.image.file.isFile }).isTrue()
        assertThat(loose.none { it.file.exists() }).isTrue()
        assertThat(session.pages.map { it.edits }).containsExactly(PageEdits(filter = PageFilter.AUTO), PageEdits(filter = PageFilter.AUTO))
        assertThat(session.pages.map { it.image.widthPx }).containsExactly(100, 101).inOrder()
        assertThat(session.createdAt).isEqualTo(rig.clock.now)
        assertThat(File(dir, "session.json").isFile).isTrue()
    }

    @Test
    fun sessionSurvivesANewProcess() = runTest {
        val targets = listOf(
            ImportTarget.NewDocument(title = "Lease", folderId = FolderId(3)),
            ImportTarget.NewDocument(),
            ImportTarget.AppendTo(DocumentId(7), atIndex = 2),
            ImportTarget.AppendTo(DocumentId(8)),
            ImportTarget.Replace(PageId(9)),
        )
        for (target in targets) {
            val session = store.create(target, images(2))
            val edited = session.copy(pages = session.pages.map { it.copy(edits = PageEdits(rotation = Rotation.R270, filter = PageFilter.GRAYSCALE)) })
            store.update(edited)
            assertThat(newStore().get(session.id)).isEqualTo(edited)
        }
    }

    @Test
    fun updateDeletesFilesOfRemovedPages() = runTest {
        val session = store.create(ImportTarget.NewDocument(), images(3))
        val removed = session.pages[1].image.file
        store.update(session.copy(pages = session.pages - session.pages[1]))
        assertThat(removed.exists()).isFalse()
        assertThat(store.get(session.id)!!.pages.map { it.image.file }).containsExactly(session.pages[0].image.file, session.pages[2].image.file).inOrder()
    }

    @Test
    fun pagesAddedLaterStayWhereTheyAreAndDiscardRemovesThem() = runTest {
        val session = store.create(ImportTarget.NewDocument(), images(1))
        val added = images(1).single() // a second scanner run during Review leaves its page in loose staging
        val combined = session.copy(pages = session.pages + StagedPage(added))
        store.update(combined)
        assertThat(newStore().get(session.id)).isEqualTo(combined)
        store.discard(session.id)
        assertThat(added.file.exists()).isFalse()
    }

    @Test
    fun getDropsPagesWhoseFileIsGoneAndTouchesTheSession() = runTest {
        val session = store.create(ImportTarget.NewDocument(), images(2))
        session.pages[0].image.file.delete()
        val json = File(File(rig.dirs.staging, session.id), "session.json")
        json.setLastModified(0)
        rig.clock.advance(Duration.ofHours(30))
        assertThat(store.get(session.id)!!.pages).containsExactly(session.pages[1])
        assertThat(json.lastModified()).isEqualTo(rig.clock.now.toEpochMilli())
    }

    @Test
    fun unknownDamagedOrInvalidSessionsAreNull() = runTest {
        assertThat(store.get("00000000-0000-0000-0000-000000000000")).isNull()
        assertThat(store.get("../../files/pages")).isNull()
        val session = store.create(ImportTarget.NewDocument(), images(1))
        File(File(rig.dirs.staging, session.id), "session.json").writeText("{ not json")
        assertThat(store.get(session.id)).isNull()
        assertThat(runCatching { store.update(session.copy(id = "../x")) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun discardDeletesEverything() = runTest {
        val session = store.create(ImportTarget.NewDocument(), images(2))
        store.discard(session.id)
        assertThat(File(rig.dirs.staging, session.id).exists()).isFalse()
        assertThat(session.pages.none { it.image.file.exists() }).isTrue()
        assertThat(store.get(session.id)).isNull()
        store.discard("not-a-session")
    }

    @Test
    fun imagesOutsideLooseStagingStayWhereTheyAre() = runTest {
        val elsewhere = File(rig.dirs.staging, "scanner-out.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(10)) }
        val image = StagedImage(elsewhere, 10, 10, "image/jpeg", heycam.core.model.PageOrigin.SCANNER)
        val session = store.create(ImportTarget.NewDocument(), listOf(image))
        assertThat(session.pages.single().image.file).isEqualTo(elsewhere)
        assertThat(newStore().get(session.id)!!.pages.single().image.file.canonicalFile).isEqualTo(elsewhere.canonicalFile)
    }
}
