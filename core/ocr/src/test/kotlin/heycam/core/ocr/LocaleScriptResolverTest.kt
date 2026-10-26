package heycam.core.ocr

import com.google.common.truth.Truth.assertThat
import heycam.core.model.OcrScript
import java.util.Locale
import org.junit.Test

class LocaleScriptResolverTest {
    private val resolver = LocaleScriptResolver()

    private fun resolve(vararg tags: String, override: OcrScript? = null, setting: OcrScript? = null) =
        resolver.resolve(override, setting, tags.map(Locale::forLanguageTag))

    @Test
    fun overrideBeatsSettingBeatsDevice() {
        assertThat(resolve("hi-IN", override = OcrScript.KOREAN, setting = OcrScript.JAPANESE)).isEqualTo(OcrScript.KOREAN)
        assertThat(resolve("hi-IN", setting = OcrScript.LATIN)).isEqualTo(OcrScript.LATIN)
        assertThat(resolve("hi-IN")).isEqualTo(OcrScript.DEVANAGARI)
    }

    @Test
    fun devanagariLanguages() {
        for (tag in listOf("hi", "mr-IN", "ne-NP", "sa", "kok", "mai", "doi", "bho")) {
            assertThat(resolve(tag)).isEqualTo(OcrScript.DEVANAGARI)
        }
    }

    @Test
    fun eastAsianLanguages() {
        assertThat(resolve("zh-CN")).isEqualTo(OcrScript.CHINESE)
        assertThat(resolve("zh-TW")).isEqualTo(OcrScript.CHINESE)
        assertThat(resolve("zh-Hant-HK")).isEqualTo(OcrScript.CHINESE)
        assertThat(resolve("ja-JP")).isEqualTo(OcrScript.JAPANESE)
        assertThat(resolve("ko-KR")).isEqualTo(OcrScript.KOREAN)
    }

    @Test
    fun everythingElseIsLatin() {
        assertThat(resolve("en-US")).isEqualTo(OcrScript.LATIN)
        assertThat(resolve("ta-IN", "bn-IN")).isEqualTo(OcrScript.LATIN) // no model for Tamil or Bengali script
        assertThat(resolve()).isEqualTo(OcrScript.LATIN)
    }

    @Test
    fun firstNonLatinLanguageWinsBecauseItsModelAlsoReadsLatin() {
        assertThat(resolve("en-IN", "hi-IN")).isEqualTo(OcrScript.DEVANAGARI)
        assertThat(resolve("en-US", "ja-JP", "ko-KR")).isEqualTo(OcrScript.JAPANESE)
    }

    @Test
    fun scriptSubtagWinsOverTheLanguage() {
        assertThat(resolve("hi-Latn")).isEqualTo(OcrScript.LATIN)
        assertThat(resolve("sd-Deva-IN")).isEqualTo(OcrScript.DEVANAGARI)
        assertThat(resolve("hi-Latn", "ko")).isEqualTo(OcrScript.KOREAN)
    }
}
