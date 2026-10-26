package heycam.core.data

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * PLAN D9: scans never reach Google cloud backup; phone-to-phone transfer (Android 12+) carries the library but not
 * the SDKs' per-installation data.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val everyDomain = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    private data class Rule(val section: String, val kind: String, val domain: String, val path: String)

    private fun rules(id: Int): Pair<String, List<Rule>> {
        val parser: XmlResourceParser = context.resources.getXml(id)
        var root = ""
        var section = ""
        val rules = mutableListOf<Rule>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "include", "exclude" -> rules += Rule(
                    section,
                    parser.name,
                    parser.getAttributeValue(null, "domain"),
                    parser.getAttributeValue(null, "path"),
                )
                else -> if (root.isEmpty()) root = parser.name else section = parser.name
            }
        }
        return root to rules
    }

    @Test
    fun cloudBackupExcludesEverythingAndDeviceTransferCarriesTheLibrary() {
        val (root, rules) = rules(R.xml.data_extraction_rules)
        assertThat(root).isEqualTo("data-extraction-rules")
        val cloud = rules.filter { it.section == "cloud-backup" }
        assertThat(cloud.map { it.kind }.toSet()).containsExactly("exclude")
        assertThat(cloud.map { it.domain }.toSet()).isEqualTo(everyDomain)
        assertThat(cloud.map { it.path }.toSet()).containsExactly(".")

        val transfer = rules.filter { it.section == "device-transfer" }
        val includes = transfer.filter { it.kind == "include" }
        assertThat(includes.map { it.domain }).containsExactly("file", "database", "sharedpref")
        assertThat(includes.map { it.path }.toSet()).containsExactly(".")
        // The SDKs' per-installation ID and queued diagnostics stay on the old phone.
        val excludes = transfer.filter { it.kind == "exclude" }.map { it.domain to it.path }
        assertThat(excludes).containsExactly(
            "sharedpref" to "com.google.mlkit.internal.xml",
            "database" to "com.google.android.datatransport.events",
        )
    }

    @Test
    fun legacyFullBackupExcludesEverything() {
        val (root, rules) = rules(R.xml.backup_rules)
        assertThat(root).isEqualTo("full-backup-content")
        assertThat(rules.map { it.kind }.toSet()).containsExactly("exclude")
        assertThat(rules.map { it.domain }.toSet()).isEqualTo(everyDomain)
        assertThat(rules.map { it.path }.toSet()).containsExactly(".")
    }
}
