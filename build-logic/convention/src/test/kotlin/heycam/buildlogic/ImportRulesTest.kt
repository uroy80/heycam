package heycam.buildlogic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImportRulesTest {
    private fun messages(module: String, vararg lines: String) = ImportRules.violations(module, lines.toList()).map { it.message }

    @Test
    fun networkCodeIsForbiddenEverywhere() {
        assertThat(messages(":core:export", "import java.net.URL")).hasSize(1)
        assertThat(messages(":core:data", "import java.net.HttpURLConnection")).hasSize(1)
        assertThat(messages(":feature:tools", "val s = java.net.Socket(host, 443)")).hasSize(1)
        assertThat(messages(":app", "import okhttp3.OkHttpClient")).hasSize(1)
        assertThat(messages(":app", "import io.ktor.client.HttpClient")).hasSize(1)
        assertThat(messages(":app", "import javax.net.ssl.HttpsURLConnection")).hasSize(1)
        assertThat(messages(":feature:settings", "import android.webkit.WebView")).hasSize(1)
    }

    @Test
    fun lookalikesAreAllowed() {
        assertThat(messages(":core:export", "import java.net.URLEncoder", "import java.net.URI", "import android.net.Uri")).isEmpty()
        assertThat(messages(":core:ocr", "import java.net.SocketTimeoutException")).isEmpty()
    }

    @Test
    fun loggingGoesThroughHeycamLog() {
        assertThat(messages(":feature:library", "import android.util.Log")).hasSize(1)
        assertThat(messages(":core:pdf", "android.util.Log.d(TAG, \"x\")")).hasSize(1)
        assertThat(messages(":core:common", "import android.util.Log")).isEmpty()
    }

    @Test
    fun modelStaysPlainKotlin() {
        assertThat(messages(":core:model", "import android.graphics.Bitmap")).hasSize(1)
        assertThat(messages(":core:model", "import kotlinx.coroutines.flow.Flow")).isEmpty()
        assertThat(messages(":core:imaging", "import android.graphics.Bitmap")).isEmpty()
    }

    @Test
    fun librariesStayBehindTheirModules() {
        assertThat(messages(":core:domain", "import com.google.mlkit.vision.text.TextRecognition")).hasSize(1)
        assertThat(messages(":core:ocr", "import com.google.mlkit.vision.text.TextRecognition")).isEmpty()
        assertThat(messages(":core:scanner", "import com.google.mlkit.vision.documentscanner.GmsDocumentScanning")).isEmpty()
        assertThat(messages(":core:export", "import com.tom_roush.pdfbox.pdmodel.PDDocument")).hasSize(1)
        assertThat(messages(":core:pdf", "import com.tom_roush.pdfbox.pdmodel.PDDocument")).isEmpty()
        assertThat(messages(":core:domain", "import androidx.room3.Transactor")).hasSize(1)
        assertThat(messages(":feature:library", "import androidx.sqlite.SQLiteConnection")).hasSize(1)
        assertThat(messages(":core:data", "import androidx.room3.RoomDatabase", "import androidx.sqlite.driver.bundled.BundledSQLiteDriver")).isEmpty()
    }

    @Test
    fun commentsAreSkippedAndLinesAreNumbered() {
        val violations = ImportRules.violations(
            ":app",
            listOf("// never java.net.URL", " * or android.util.Log", "/* android.webkit.WebView */", "", "import java.net.URL"),
        )
        assertThat(violations.map { it.line }).containsExactly(5)
    }
}
