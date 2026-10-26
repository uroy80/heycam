package heycam.core.ocr

import heycam.core.model.ImageInfo
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.TextLine
import java.util.Locale
import kotlinx.coroutines.flow.Flow

interface OcrEngine {
    /** Decodes [image] (<= 3000 px long edge), runs ML Kit text recognition v2 for [script]. Text is NFC-normalised. */
    suspend fun recognize(image: ImageInfo, script: OcrScript): OcrOutcome
    /** Checks Play services model availability and requests the download through ModuleInstallClient when missing. */
    fun ensureModels(scripts: Set<OcrScript>): Flow<ModuleState>
}

sealed interface OcrOutcome {
    data class Recognized(
        val text: String,
        val lines: List<TextLine>,
        val widthPx: Int,
        val heightPx: Int,
        val durationMs: Long,
        val engine: String,
    ) : OcrOutcome
    data class ModelNotReady(val state: ModuleState) : OcrOutcome
    data class Failed(val errorClass: String) : OcrOutcome   // never contains document text
}

/**
 * override (per document) > setting (Settings.ocrScript) > device languages:
 * hi, mr, ne, sa, kok, mai, doi, bho -> DEVANAGARI; zh -> CHINESE; ja -> JAPANESE; ko -> KOREAN; else LATIN.
 */
fun interface ScriptResolver {
    fun resolve(override: OcrScript?, setting: OcrScript?, deviceLocales: List<Locale>): OcrScript
}
