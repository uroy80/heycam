package heycam.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.SystemClock
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.model.ImageInfo
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.ocr.ModelInstall.isTerminal
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * ML Kit text recognition v2, unbundled: the models live in Google Play services (D5, gate G1). Latin and Devanagari
 * are requested at install time through the manifest; Chinese, Japanese and Korean download on first use.
 *
 * A model that is not on the phone yet makes [recognize] answer [OcrOutcome.ModelNotReady] with
 * `Downloading(0, null)`: it has to be fetched, which [ensureModels] does.
 */
@Singleton
class MlKitOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val log: HeycamLog,
) : OcrEngine {
    private val recognizers = ConcurrentHashMap<OcrScript, TextRecognizer>()
    private val modelsPresent: MutableSet<OcrScript> = ConcurrentHashMap.newKeySet()
    private val installer: ModuleInstallClient by lazy { ModuleInstall.getClient(context) }

    private fun recognizer(script: OcrScript): TextRecognizer = recognizers.getOrPut(script) {
        TextRecognition.getClient(
            when (script) {
                OcrScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                OcrScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                OcrScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                OcrScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                OcrScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            },
        )
    }

    override suspend fun recognize(image: ImageInfo, script: OcrScript): OcrOutcome {
        ModelInstall.playServices(context)?.let { return OcrOutcome.ModelNotReady(ModuleState.Unsupported(it)) }
        val recognizer = recognizer(script)
        if (script !in modelsPresent) {
            val present = try {
                installer.areModulesAvailable(recognizer).await().areModulesAvailable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "Model check failed for $script", e)
                return OcrOutcome.ModelNotReady(ModelInstall.failureOf(e))
            }
            if (!present) return OcrOutcome.ModelNotReady(NOT_DOWNLOADED)
            modelsPresent += script
        }
        val bitmap = try {
            withContext(dispatchers.default) { decode(File(image.path)) }
        } catch (e: IOException) {
            log.w(TAG, "Page image unreadable", e)
            return OcrOutcome.Failed(e.javaClass.simpleName)
        } catch (e: OutOfMemoryError) {
            log.w(TAG, "Page image too large to read")
            return OcrOutcome.Failed("OutOfMemoryError")
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val duration = SystemClock.elapsedRealtime() - started
            val page = withContext(dispatchers.default) {
                TextMapping.map(result, image.widthPx / bitmap.width.toFloat(), image.heightPx / bitmap.height.toFloat())
            }
            return OcrOutcome.Recognized(page.text, page.lines, image.widthPx, image.heightPx, duration, engine(script))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MlKitException) {
            if (e.errorCode == MlKitException.UNAVAILABLE) {
                modelsPresent -= script
                return OcrOutcome.ModelNotReady(NOT_DOWNLOADED)
            }
            log.w(TAG, "Recognition failed for $script (code ${e.errorCode})", e)
            return OcrOutcome.Failed("MlKitException:${e.errorCode}")
        } catch (e: Exception) {
            log.w(TAG, "Recognition failed for $script", e)
            return OcrOutcome.Failed(e.javaClass.simpleName)
        } finally {
            bitmap.recycle()
        }
    }

    override fun ensureModels(scripts: Set<OcrScript>): Flow<ModuleState> = flow {
        emit(ModuleState.Checking)
        ModelInstall.playServices(context)?.let {
            emit(ModuleState.Unsupported(it))
            return@flow
        }
        val wanted = scripts.ifEmpty { setOf(OcrScript.LATIN) }
        val apis = wanted.map(::recognizer)
        val present = try {
            installer.areModulesAvailable(*apis.toTypedArray()).await().areModulesAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "Model check failed", e)
            emit(ModelInstall.failureOf(e))
            return@flow
        }
        if (present) {
            modelsPresent += wanted
            emit(ModuleState.Ready)
            return@flow
        }
        if (!ModelInstall.online(context)) {
            emit(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
            return@flow
        }
        emitAll(
            ModelInstall.install(installer, apis)
                .onEach { if (it == ModuleState.Ready) modelsPresent += wanted }
                .transformWhile { emit(it); !it.isTerminal() },
        )
    }

    /** Software ARGB bitmap, long edge at most 3000 px (PLAN 3.7). Pages carry no EXIF, so nothing is rotated. */
    private fun decode(file: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val (w, h) = TextMapping.decodeSize(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    companion object {
        private const val TAG = "Ocr"
        private val NOT_DOWNLOADED = ModuleState.Downloading(bytesDownloaded = 0, totalBytes = null)

        fun engine(script: OcrScript): String =
            "mlkit-text-v2/" + if (script == OcrScript.LATIN) BuildConfig.MLKIT_TEXT_LATIN else BuildConfig.MLKIT_TEXT_SCRIPTS
    }
}
