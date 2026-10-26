package heycam.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import heycam.core.export.ExportFileProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** The merged manifest keeps the promises of PLAN §3.5. */
@RunWith(AndroidJUnit4::class)
class ManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packageManager = context.packageManager

    @Test
    fun exportsAreSharedOnlyFromTheExportsCache() {
        val authority = "${context.packageName}.exports"
        val export = File(context.cacheDir, "exports/abc/Scan - p01.jpg")
        val uri = FileProvider.getUriForFile(context, authority, export)
        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo(authority)

        val page = File(context.filesDir, "pages/key/source.jpg")
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, authority, page) }

        val provider = packageManager.getProviderInfo(ComponentName(context, ExportFileProvider::class.java), 0)
        assertThat(provider.exported).isFalse()
        assertThat(provider.grantUriPermissions).isTrue()
    }

    @Test
    fun sharedImagesOpenHeycam() {
        listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE).forEach { action ->
            val share = Intent(action).setType("image/jpeg").setPackage(context.packageName)
            val targets = packageManager.queryIntentActivities(share, 0).map { it.activityInfo.name }
            assertThat(targets).containsExactly(MainActivity::class.java.name)
        }
        val pdf = Intent(Intent.ACTION_SEND).setType("application/pdf").setPackage(context.packageName)
        assertThat(packageManager.queryIntentActivities(pdf, 0)).isEmpty()
    }

    @Test
    fun backupAndTextModels() {
        val app = packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertThat(app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isNotEqualTo(0)
        assertThat(app.metaData.getString("com.google.mlkit.vision.DEPENDENCIES")).isEqualTo("ocr,ocr_devanagari")
    }
}
