package heycam.core.ocr

import heycam.core.model.OcrScript
import java.util.Locale
import javax.inject.Inject

/**
 * PLAN 3.9 / D20: the document's override, then Settings, then the device's languages in order. The first language
 * written in Devanagari, Chinese, Japanese or Korean picks that model; with none, Latin. Every non-Latin model also
 * reads Latin text (G1: Devanagari read the English lines too), so "en, hi" reads as Devanagari and keeps both.
 */
class LocaleScriptResolver @Inject constructor() : ScriptResolver {
    override fun resolve(override: OcrScript?, setting: OcrScript?, deviceLocales: List<Locale>): OcrScript =
        override ?: setting ?: deviceLocales.firstNotNullOfOrNull(::nonLatinScriptOf) ?: OcrScript.LATIN

    companion object {
        private val DEVANAGARI_LANGUAGES = setOf("hi", "mr", "ne", "sa", "kok", "mai", "doi", "bho")

        /**
         * The non-Latin script [locale] is written in, or null for Latin and anything heycam has no model for.
         * An explicit script subtag wins over the language, so "hi-Latn" (Hinglish) stays Latin.
         */
        fun nonLatinScriptOf(locale: Locale): OcrScript? {
            when (locale.script) {
                "Deva" -> return OcrScript.DEVANAGARI
                "Hans", "Hant", "Hani" -> return OcrScript.CHINESE
                "Jpan", "Hira", "Kana" -> return OcrScript.JAPANESE
                "Kore", "Hang" -> return OcrScript.KOREAN
                "" -> Unit
                else -> return null
            }
            return when (locale.language) {
                in DEVANAGARI_LANGUAGES -> OcrScript.DEVANAGARI
                "zh" -> OcrScript.CHINESE
                "ja" -> OcrScript.JAPANESE
                "ko" -> OcrScript.KOREAN
                else -> null
            }
        }
    }
}
