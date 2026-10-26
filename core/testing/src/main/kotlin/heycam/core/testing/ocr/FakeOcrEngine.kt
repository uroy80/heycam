package heycam.core.testing.ocr

import heycam.core.model.ImageInfo
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.ocr.OcrEngine
import heycam.core.ocr.OcrOutcome
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [OcrEngine] without ML Kit: every image reads as [sample]'s lines, scaled to the image's size and limited to what
 * the chosen script's model can read (the Latin model drops Hindi, as the spike found). Models outside [installed]
 * answer [OcrOutcome.ModelNotReady] until [ensureModels] installs them, ending in [installResult].
 * [answer] fixes the outcome for one image path.
 */
class FakeOcrEngine(var sample: OcrSample = OcrSample.EN_HI) : OcrEngine {
    data class Call(val image: ImageInfo, val script: OcrScript)

    private val _calls = Collections.synchronizedList(mutableListOf<Call>())
    val calls: List<Call> get() = synchronized(_calls) { _calls.toList() }

    val installed: MutableSet<OcrScript> = ConcurrentHashMap.newKeySet<OcrScript>().apply { addAll(OcrScript.entries) }

    @Volatile var installResult: ModuleState = ModuleState.Ready

    private val answers = ConcurrentHashMap<String, OcrOutcome>()

    fun answer(imagePath: String, outcome: OcrOutcome) {
        answers[imagePath] = outcome
    }

    override suspend fun recognize(image: ImageInfo, script: OcrScript): OcrOutcome {
        _calls += Call(image, script)
        answers[image.path]?.let { return it }
        if (script !in installed) return OcrOutcome.ModelNotReady(ModuleState.Downloading(0, null))
        val sx = image.widthPx / sample.widthPx.toFloat()
        val sy = image.heightPx / sample.heightPx.toFloat()
        val lines = sample.lines.filter { readable(it.text, script) }.map { it.copy(box = it.box.scaled(sx, sy)) }
        return OcrOutcome.Recognized(lines.joinToString("\n") { it.text }, lines, image.widthPx, image.heightPx, 1, ENGINE)
    }

    override fun ensureModels(scripts: Set<OcrScript>): Flow<ModuleState> = flow {
        emit(ModuleState.Checking)
        if (installed.containsAll(scripts)) {
            emit(ModuleState.Ready)
            return@flow
        }
        val result = installResult
        if (result == ModuleState.Ready) {
            emit(ModuleState.Downloading(0, MODEL_BYTES))
            emit(ModuleState.Downloading(MODEL_BYTES, MODEL_BYTES))
            emit(ModuleState.Installing)
            installed += scripts
        }
        emit(result)
    }

    companion object {
        const val ENGINE = "fake-ocr/1"
        private const val MODEL_BYTES = 2_107_714L

        private val COMMON = setOf(Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.LATIN)

        /** Every letter in [text] belongs to a script [script]'s model reads. */
        fun readable(text: String, script: OcrScript): Boolean {
            val extra = when (script) {
                OcrScript.LATIN -> emptySet()
                OcrScript.DEVANAGARI -> setOf(Character.UnicodeScript.DEVANAGARI)
                OcrScript.CHINESE -> setOf(Character.UnicodeScript.HAN)
                OcrScript.JAPANESE -> setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
                OcrScript.KOREAN -> setOf(Character.UnicodeScript.HANGUL)
            }
            return text.codePoints().allMatch { Character.UnicodeScript.of(it).let { s -> s in COMMON || s in extra } }
        }
    }
}
