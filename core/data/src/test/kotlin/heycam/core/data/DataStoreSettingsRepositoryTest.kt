package heycam.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.data.di.DataModule
import heycam.core.model.AppLockSettings
import heycam.core.model.ExportFormat
import heycam.core.model.ExportOptions
import heycam.core.model.Margin
import heycam.core.model.OcrScript
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.PageSize
import heycam.core.model.Password
import heycam.core.model.Settings
import heycam.core.model.SizePreset
import heycam.core.model.ThemeSetting
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataStoreSettingsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file by lazy { File(temp.root, "settings.preferences_pb") }
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }) }
    private val log = RecordingLog()
    private val repo by lazy { DataStoreSettingsRepository(store, log) }

    @After fun tearDown() = scope.cancel()

    @Test
    fun startsWithDefaults() = runTest {
        assertThat(repo.settings.first()).isEqualTo(Settings())
    }

    @Test
    fun everyLastingChoiceRoundTrips() = runTest {
        val chosen = Settings(
            theme = ThemeSetting.NIGHT_INK,
            ocrScript = OcrScript.KOREAN,
            autoText = false,
            defaultFilter = PageFilter.BLACK_WHITE,
            exportDefaults = ExportOptions(
                format = ExportFormat.PNG,
                size = SizePreset.SMALL,
                pageSize = PageSize.LEGAL,
                margin = Margin.NARROW,
                searchable = false,
                pageNumbers = true,
            ),
            trashRetentionDays = 7,
            appLock = AppLockSettings(enabled = true, relockAfterSeconds = 60),
            secureWindow = true,
            onboardingSeen = true,
        )
        repo.update { chosen }
        assertThat(repo.settings.first()).isEqualTo(chosen)
        // A second repository over the same file sees the same values (a new process).
        assertThat(DataStoreSettingsRepository(store, log).settings.first()).isEqualTo(chosen)

        repo.update { it.copy(ocrScript = null) }
        assertThat(repo.settings.first().ocrScript).isNull()
    }

    @Test
    fun perExportValuesAreNeverStored() = runTest {
        val password = Password("secret".toCharArray())
        repo.update {
            it.copy(exportDefaults = ExportOptions(pages = listOf(PageId(1)), fileName = "Tax return", password = password, watermark = "DRAFT"))
        }
        val stored = repo.settings.first().exportDefaults
        assertThat(stored.pages).isNull()
        assertThat(stored.fileName).isNull()
        assertThat(stored.password).isNull()
        assertThat(stored.watermark).isNull()
        assertThat(file.readBytes().toString(Charsets.ISO_8859_1)).doesNotContain("secret")
        assertThat(file.readBytes().toString(Charsets.ISO_8859_1)).doesNotContain("Tax return")
    }

    @Test
    fun unknownOrBrokenValuesFallBackToDefaults() = runTest {
        store.edit {
            it[stringPreferencesKey("theme")] = "NEON"
            it[stringPreferencesKey("ocr_script")] = "KLINGON"
            it[stringPreferencesKey("default_filter")] = "SEPIA"
            it[androidx.datastore.preferences.core.intPreferencesKey("trash_retention_days")] = -3
        }
        val settings = repo.settings.first()
        assertThat(settings.theme).isEqualTo(ThemeSetting.SYSTEM)
        assertThat(settings.ocrScript).isNull()
        assertThat(settings.defaultFilter).isEqualTo(PageFilter.ORIGINAL)
        assertThat(settings.trashRetentionDays).isEqualTo(30)
    }

    @Test
    fun emitsOnlyRealChanges() = runTest {
        repo.settings.test {
            assertThat(awaitItem()).isEqualTo(Settings())
            repo.update { it }
            repo.update { it.copy(autoText = false) }
            assertThat(awaitItem().autoText).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aDamagedSettingsFileIsReplacedWithDefaults() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.preferencesDataStoreFile("settings").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x0a, 0x7f, 0x00, 0x13, 0x55))
        }
        val production = DataModule.settingsStore(context, TestDispatchers, scope)
        val settings = DataStoreSettingsRepository(production, log)
        assertThat(settings.settings.first()).isEqualTo(Settings())
        settings.update { it.copy(onboardingSeen = true) }
        assertThat(settings.settings.first().onboardingSeen).isTrue()
    }
}
