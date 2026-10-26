package heycam.core.ocr

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.model.ImageInfo
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate G1 and its M0 follow-ups on a device with Google Play services: every script's model installs through
 * ModuleInstallClient (Chinese, Japanese and Korean on demand) and MlKitOcrEngine reads the samples. Run once
 * online, then again in flight mode to check reading works offline after the install.
 */
@RunWith(AndroidJUnit4::class)
class UnbundledOcrGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = engine(context)

    companion object {
        /**
         * Every model goes in before any recogniser loads. Play services keeps the text models in one container;
         * installing a new one while this process has another loaded changes that container, and Play services
         * then kills the process ("Module config changed, forcing restart"), which ends the test run.
         */
        @JvmStatic
        @BeforeClass
        fun installEveryModelFirst() = runBlocking {
            val installed = withTimeout(600_000) {
                engine(ApplicationProvider.getApplicationContext()).ensureModels(OcrScript.entries.toSet()).last()
            }
            assertWithMessage("every text model").that(installed).isEqualTo(ModuleState.Ready)
        }

        private fun engine(context: Context) = MlKitOcrEngine(
            context = context,
            dispatchers = object : DispatcherProvider {
                override val io: CoroutineDispatcher = Dispatchers.IO
                override val default: CoroutineDispatcher = Dispatchers.Default
                override val main: CoroutineDispatcher = Dispatchers.Main
            },
            log = object : HeycamLog {
                override fun d(tag: String, message: String) = Unit
                override fun w(tag: String, message: String, error: Throwable?) = Unit
                override fun e(tag: String, message: String, error: Throwable?) = Unit
            },
        )
    }

    @Test
    fun latinAndDevanagari() = runBlocking {
        val page = asset("sample_doc.jpg")
        assertReads(page, OcrScript.LATIN, "Invoice")
        assertReads(page, OcrScript.DEVANAGARI, "धन्यवाद", "Invoice")
    }

    @Test
    fun chineseJapaneseAndKorean() = runBlocking {
        val page = asset("sample_cjk.jpg")
        assertReads(page, OcrScript.JAPANESE, "東京駅", "Tokyo")
        assertReads(page, OcrScript.KOREAN, "서울역", "Tokyo")
        assertReads(page, OcrScript.CHINESE, "北京", "Tokyo")
    }

    private suspend fun assertReads(page: ImageInfo, script: OcrScript, vararg expected: String) {
        val installed = withTimeout(300_000) { engine.ensureModels(setOf(script)).last() }
        assertWithMessage("$script model").that(installed).isEqualTo(ModuleState.Ready)
        repeat(2) { // cold, then warm
            val outcome = engine.recognize(page, script)
            assertThat(outcome).isInstanceOf(OcrOutcome.Recognized::class.java)
            val recognized = outcome as OcrOutcome.Recognized
            expected.forEach { assertWithMessage("$script text").that(recognized.text).contains(it) }
            assertThat(recognized.lines).isNotEmpty()
            assertThat(recognized.engine).startsWith("mlkit-text-v2/")
        }
    }

    private fun asset(name: String): ImageInfo {
        val file = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return ImageInfo(file.path, bounds.outWidth, bounds.outHeight, file.length(), "image/jpeg")
    }
}
