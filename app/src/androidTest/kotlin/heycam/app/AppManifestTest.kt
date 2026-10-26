package heycam.app

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import heycam.feature.settings.LicenceText
import heycam.feature.settings.OpenSourceLicences
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * The installed app keeps the privacy promises of PLAN §3.5, §3.8 and D9, read back from the package manager and
 * the merged resources rather than from source files.
 */
@RunWith(AndroidJUnit4::class)
class AppManifestTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm = context.packageManager
    private val pkg = context.packageName

    @Test
    fun onlyAllowlistedPermissions() {
        val requested = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val allowed = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        assertWithMessage("permissions outside config/permissions-allowlist.txt").that(requested - allowed).isEmpty()
    }

    @Test
    fun noCleartextTrafficAndBackupFollowsTheRules() {
        val info = pm.getApplicationInfo(pkg, 0)
        assertThat(info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC).isEqualTo(0)
        assertThat(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isNotEqualTo(0) // D9: on, but see the rules below
    }

    /** D9: nothing goes to Google's cloud backup; phone-to-phone transfer carries files, database and settings. */
    @Test
    fun scansNeverGoToCloudBackup() {
        val domains = listOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        val extraction = elements("data_extraction_rules")
        val cloudExcludes = extraction.filter { it.section == "cloud-backup" && it.tag == "exclude" }.map { it.domain }
        assertThat(cloudExcludes).containsAtLeastElementsIn(domains)
        assertThat(extraction.filter { it.section == "cloud-backup" && it.tag == "include" }).isEmpty()
        val transferIncludes = extraction.filter { it.section == "device-transfer" && it.tag == "include" }.map { it.domain }
        assertThat(transferIncludes).containsAtLeast("file", "database", "sharedpref")

        // API 28-30 read fullBackupContent: everything excluded, nothing included.
        val legacy = elements("backup_rules")
        assertThat(legacy.filter { it.tag == "include" }).isEmpty()
        assertThat(legacy.filter { it.tag == "exclude" }.map { it.domain }).containsAtLeast("root", "file", "database", "sharedpref", "external")
    }

    /** P6: exports leave only through per-intent grants on a provider nobody else can open. */
    @Test
    fun exportProviderIsPrivate() {
        val provider = pm.resolveContentProvider("$pkg.exports", 0)
        assertThat(provider).isNotNull()
        assertThat(provider!!.exported).isFalse()
        assertThat(provider.grantUriPermissions).isTrue()
    }

    /** F3 and F11: shares of images reach MainActivity; the shortcut and tile actions resolve to it. */
    @Test
    fun mainActivityTakesSharesAndEntryActions() {
        val main = "heycam.app.MainActivity"
        listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE).forEach { action ->
            val share = Intent(action).setType("image/jpeg").setPackage(pkg)
            val handlers = pm.queryIntentActivities(share, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.name }
            assertWithMessage(action).that(handlers).contains(main)
        }
        val pdfShare = Intent(Intent.ACTION_SEND).setType("application/pdf").setPackage(pkg)
        assertWithMessage("PDF intake arrives in v1.1 (D15)").that(pm.queryIntentActivities(pdfShare, 0)).isEmpty()
        listOf("heycam.action.SCAN", "heycam.action.IMPORT", "heycam.action.SEARCH").forEach { action ->
            assertWithMessage(action).that(pm.resolveActivity(Intent(action).setClassName(pkg, main), 0)).isNotNull()
        }
    }

    /**
     * API 37 ignores orientation locks on large screens; heycam's own activities declare none (§3.5). ML Kit's
     * GmsDocumentScanningDelegateActivity, a trampoline into Google's scanner, keeps the portrait lock it ships with.
     */
    @Test
    fun noOrientationLocks() {
        val activities = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities.orEmpty()
            .filter { it.name.startsWith("heycam.") }
        assertThat(activities).isNotEmpty()
        activities.forEach { activity ->
            assertWithMessage(activity.name).that(activity.screenOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        }
    }

    /** Every licence text the Licences screen opens from assets is in the APK (font OFLs come from :core:designsystem). */
    @Test
    fun licenceTextsAreBundled() {
        OpenSourceLicences.components.map { it.text }.filterIsInstance<LicenceText.Asset>().distinct().forEach { asset ->
            val text = context.assets.open(asset.path).bufferedReader().use { it.readText() }
            assertWithMessage(asset.path).that(text.length).isGreaterThan(1_000)
        }
    }

    private data class RuleElement(val section: String?, val tag: String, val domain: String?)

    /** The include/exclude elements of res/xml/<name>, with the section (cloud-backup, device-transfer) they sit in. */
    private fun elements(name: String): List<RuleElement> {
        val id = context.resources.getIdentifier(name, "xml", pkg)
        assertWithMessage("res/xml/$name.xml").that(id).isNotEqualTo(0)
        val out = mutableListOf<RuleElement>()
        context.resources.getXml(id).use { parser: XmlResourceParser ->
            var section: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "cloud-backup", "device-transfer" -> section = parser.name
                        "include", "exclude" -> out += RuleElement(section, parser.name, parser.getAttributeValue(null, "domain"))
                    }
                    XmlPullParser.END_TAG -> if (parser.name == section) section = null
                }
            }
        }
        return out
    }
}
