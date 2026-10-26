package heycam.core.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.common.StagingArea
import heycam.core.model.PageOrigin
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanPageStagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var stagingDir: File
    private lateinit var mlkitDir: File
    private lateinit var stager: ScanPageStager

    @Before
    fun setUp() {
        stagingDir = tmp.newFolder("staging")
        mlkitDir = File(context.cacheDir, "mlkit_docscan_ui_client").apply { mkdirs() }
        stager = ScanPageStager(context, staging())
    }

    private fun staging() = StagingArea { ext -> File.createTempFile("page", ".$ext", stagingDir) }

    private fun scannerFile(name: String, bytes: ByteArray): Uri = Uri.fromFile(File(mlkitDir, name).apply { writeBytes(bytes) })

    @Test
    fun pagesAreCopiedWithoutMetadataAndMlKitsCopiesDeleted() {
        val plain = TestJpegs.plain(400, 600)
        val uris = listOf(
            scannerFile("p1.jpg", TestJpegs.withSegments(plain, TestJpegs.exif(orientation = 1))),
            scannerFile("p2.jpg", TestJpegs.plain(300, 200)),
        )
        val staged = stager.stage(uris)
        assertThat(staged.map { it.widthPx to it.heightPx }).containsExactly(400 to 600, 300 to 200).inOrder()
        assertThat(staged.map { it.origin }.toSet()).containsExactly(PageOrigin.SCANNER)
        assertThat(staged.map { it.mime }.toSet()).containsExactly("image/jpeg")
        staged.forEach { assertThat(it.file.parentFile).isEqualTo(stagingDir) }
        val first = staged[0].file.readBytes()
        assertThat(TestJpegs.markers(first)).doesNotContain(0xE1)
        assertThat(TestJpegs.scanData(first)).isEqualTo(TestJpegs.scanData(plain))
        assertThat(mlkitDir.listFiles()).isEmpty()
    }

    @Test
    fun aMotionPhotoPickedInTheScannerKeepsOnlyItsStillImage() {
        val plain = TestJpegs.plain(320, 240)
        val video = "\u0000\u0000\u0000\u0018ftypmp42 moov".toByteArray(Charsets.ISO_8859_1)
        val uri = scannerFile("motion.jpg", TestJpegs.withSegments(plain, TestJpegs.exif(orientation = 1)) + video)

        val page = stager.stage(listOf(uri)).single().file.readBytes()

        assertThat(TestJpegs.markers(page)).doesNotContain(0xE1)
        assertThat(TestJpegs.scanData(page)).isEqualTo(TestJpegs.scanData(plain))
        assertThat(String(page, Charsets.ISO_8859_1)).doesNotContain("ftyp")
    }

    @Test
    fun aBrokenPageStagesNothingAndStillCleansUp() {
        val uris = listOf(
            scannerFile("ok.jpg", TestJpegs.plain(10, 10)),
            scannerFile("broken.jpg", "not a jpeg".toByteArray()),
        )
        assertThrows(IOException::class.java) { stager.stage(uris) }
        assertThat(stagingDir.listFiles()).isEmpty()
        assertThat(mlkitDir.listFiles()).isEmpty()
    }

    @Test
    fun filesOutsideTheCacheAreNeitherReadNorDeleted() {
        val outside = tmp.newFile("private.jpg").apply { writeBytes(TestJpegs.plain(10, 10)) }
        assertThrows(SecurityException::class.java) { stager.stage(listOf(Uri.fromFile(outside))) }
        assertThat(outside.exists()).isTrue()
        assertThat(stagingDir.listFiles()).isEmpty()
    }

    @Test
    fun atMostAHundredPagesAreKept() {
        val bytes = TestJpegs.plain(8, 8)
        val uris = (1..101).map { scannerFile("p$it.jpg", bytes) }
        assertThat(stager.stage(uris)).hasSize(100)
        assertThat(mlkitDir.listFiles()).isEmpty()
    }

    @Test
    fun theScannerItselfStopsAtTheMostPagesKept() {
        assertThat(GmsScannerLauncher.pageLimitFor(ScanRequest())).isEqualTo(100)
        assertThat(GmsScannerLauncher.pageLimitFor(ScanRequest(pageLimit = 1))).isEqualTo(1)
        assertThat(GmsScannerLauncher.pageLimitFor(ScanRequest(pageLimit = 500))).isEqualTo(100)
    }

    @Test
    fun finishMapsCancelAndErrorsWithoutTouchingMlKit() = runBlocking {
        val launcher = GmsScannerLauncher(context, staging(), InPlace, SilentLog)
        assertThat(launcher.finish(Activity.RESULT_CANCELED, null)).isEqualTo(ScanResult.Cancelled)
        assertThat(launcher.finish(Activity.RESULT_FIRST_USER, null)).isInstanceOf(ScanResult.Failed::class.java)
        assertThat(launcher.finish(Activity.RESULT_OK, Intent())).isEqualTo(ScanResult.Cancelled)
    }

    private object InPlace : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private object SilentLog : HeycamLog {
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, error: Throwable?) = Unit
        override fun e(tag: String, message: String, error: Throwable?) = Unit
    }
}
