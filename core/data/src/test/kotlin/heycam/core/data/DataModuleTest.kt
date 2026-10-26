package heycam.core.data

import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import heycam.core.common.StagingArea
import heycam.core.model.Settings
import heycam.core.model.SortOrder
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The production Hilt graph of :core:data (with :core:common's providers) builds and wires one store per role. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DataModuleTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var documents: DocumentRepository
    @Inject lateinit var pageWork: PageWorkStore
    @Inject lateinit var textEditor: PageTextEditor
    @Inject lateinit var folders: FolderRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var sessions: ScanSessionStore
    @Inject lateinit var files: FileStore
    @Inject lateinit var staging: StagingArea

    @Before fun inject() = hilt.inject()

    @Test
    fun oneInstancePerRole() {
        assertThat(pageWork).isSameInstanceAs(documents)
        assertThat(textEditor).isSameInstanceAs(documents)
        assertThat(staging).isSameInstanceAs(files)
    }

    @Test
    fun savesADocumentEndToEndOnDisk() = runTest {
        assertThat(settings.settings.first()).isEqualTo(Settings())
        val staged = staging.newStagedFile("jpg").apply { writeBytes(ByteArray(64)) }
        val session = sessions.create(
            heycam.core.model.ImportTarget.NewDocument(),
            listOf(heycam.core.model.StagedImage(staged, 10, 20, "image/jpeg", heycam.core.model.PageOrigin.PHOTO_PICKER)),
        )
        val folder = folders.create("Inbox", null)
        val id = documents.createDocument("First scan", folder, session.pages)
        sessions.discard(session.id)
        val doc = documents.observeDocument(id).first()!!
        assertThat(File(doc.pages.single().source.path).length()).isEqualTo(64)
        assertThat(documents.observeLibrary(folder, SortOrder.UPDATED_DESC).first().documents.single().title).isEqualTo("First scan")
        assertThat(files.usage().pagesBytes).isEqualTo(64)
    }
}
