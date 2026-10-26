package heycam.app

import android.Manifest
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.os.SystemClock
import android.provider.MediaStore
import android.service.quicksettings.TileService
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import heycam.app.entry.ScanTileService
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** F11: the Quick Settings tile and the launcher shortcuts are installed and land in MainActivity. */
@HiltAndroidTest
class EntryPointsTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pkg = context.packageName
    private val main = MainActivity::class.java.name

    @Before
    fun setUp() {
        hilt.inject()
        Intents.init()
        // Nothing outside heycam opens: the Photo Picker and the scanner answer "cancelled".
        intending(not(isInternal())).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
    }

    @After
    fun tearDown() = Intents.release()

    @Test
    fun shortcutsScanImportSearch() {
        val shortcuts = context.getSystemService(ShortcutManager::class.java).manifestShortcuts.associateBy { it.id }
        assertThat(shortcuts.keys).containsExactly("scan", "import", "search")
        mapOf("scan" to "heycam.action.SCAN", "import" to "heycam.action.IMPORT", "search" to "heycam.action.SEARCH")
            .forEach { (id, action) ->
                val shortcut = shortcuts.getValue(id)
                val intent = shortcut.intent!!
                assertWithMessage(id).that(intent.action).isEqualTo(action)
                assertWithMessage(id).that(intent.component?.className).isEqualTo(main)
                assertWithMessage(id).that(shortcut.shortLabel.toString()).isNotEmpty()
                assertWithMessage(id).that(context.packageManager.resolveActivity(intent, 0)).isNotNull()
            }
    }

    @Test
    fun scanTileIsDeclaredForTheSystemOnly() {
        val services = context.packageManager.queryIntentServices(
            Intent(TileService.ACTION_QS_TILE).setPackage(pkg),
            PackageManager.GET_META_DATA,
        )
        val tile = services.single { it.serviceInfo.name == ScanTileService::class.java.name }.serviceInfo
        assertThat(tile.permission).isEqualTo(Manifest.permission.BIND_QUICK_SETTINGS_TILE)
        assertThat(tile.exported).isTrue()
        assertThat(tile.loadLabel(context.packageManager).toString()).isEqualTo("Scan")
        assertThat(tile.iconResource).isNotEqualTo(0)

        val open = ScanTileService.scanIntent(context)
        assertThat(open.action).isEqualTo("heycam.action.SCAN")
        assertThat(context.packageManager.resolveActivity(open, 0)?.activityInfo?.name).isEqualTo(main)
    }

    @Test
    fun searchShortcutOpensHeycam() {
        ActivityScenario.launch<MainActivity>(entryIntent("heycam.action.SEARCH")).use { scenario ->
            // Not waitForIdleSync: Search focuses its field, and the blinking cursor keeps the main thread busy.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {}
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun importShortcutOpensThePhotoPicker() {
        ActivityScenario.launch<MainActivity>(entryIntent("heycam.action.IMPORT")).use {
            val picker = awaitIntent { it.action == MediaStore.ACTION_PICK_IMAGES || it.action == Intent.ACTION_GET_CONTENT }
            assertWithMessage("Photo Picker launched").that(picker).isNotNull()
            assertThat(picker!!.type).startsWith("image/")
        }
    }

    private fun entryIntent(action: String) = Intent(context, MainActivity::class.java).setAction(action)

    /** Espresso-Intents records every startActivity; the picker starts from a composition effect, so poll briefly. */
    private fun awaitIntent(timeoutMs: Long = 10_000, match: (Intent) -> Boolean): Intent? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {}
            Intents.getIntents().firstOrNull(match)?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }
}
