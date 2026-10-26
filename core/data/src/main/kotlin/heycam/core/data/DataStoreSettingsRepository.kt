package heycam.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import heycam.core.common.HeycamLog
import heycam.core.model.AppLockSettings
import heycam.core.model.ExportOptions
import heycam.core.model.OcrScript
import heycam.core.model.Settings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Settings in the DataStore Preferences file "settings". Only lasting choices are stored: per-export values
 * (page subset, file name, password, watermark) never are, and a password never touches disk (P8).
 */
@Singleton
internal class DataStoreSettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
    private val log: HeycamLog,
) : SettingsRepository {

    override val settings: Flow<Settings> = store.data
        .catch { error ->
            if (error !is IOException) throw error
            log.w(TAG, "settings unreadable, using defaults", error)
            emit(emptyPreferences())
        }
        .map(::read)
        .distinctUntilChanged()

    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val ocrScript = stringPreferencesKey("ocr_script")
        val autoText = booleanPreferencesKey("auto_text")
        val defaultFilter = stringPreferencesKey("default_filter")
        val exportFormat = stringPreferencesKey("export_format")
        val exportSize = stringPreferencesKey("export_size")
        val exportPageSize = stringPreferencesKey("export_page_size")
        val exportMargin = stringPreferencesKey("export_margin")
        val exportSearchable = booleanPreferencesKey("export_searchable")
        val exportPageNumbers = booleanPreferencesKey("export_page_numbers")
        val trashRetentionDays = intPreferencesKey("trash_retention_days")
        val appLockEnabled = booleanPreferencesKey("app_lock_enabled")
        val appLockRelockAfter = intPreferencesKey("app_lock_relock_after_s")
        val secureWindow = booleanPreferencesKey("secure_window")
        val onboardingSeen = booleanPreferencesKey("onboarding_seen")
    }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        val e = d.exportDefaults
        return Settings(
            theme = enumOr(p[Keys.theme], d.theme),
            ocrScript = enumOrNull<OcrScript>(p[Keys.ocrScript]),
            autoText = p[Keys.autoText] ?: d.autoText,
            defaultFilter = enumOr(p[Keys.defaultFilter], d.defaultFilter),
            exportDefaults = ExportOptions(
                format = enumOr(p[Keys.exportFormat], e.format),
                size = enumOr(p[Keys.exportSize], e.size),
                pageSize = enumOr(p[Keys.exportPageSize], e.pageSize),
                margin = enumOr(p[Keys.exportMargin], e.margin),
                searchable = p[Keys.exportSearchable] ?: e.searchable,
                pageNumbers = p[Keys.exportPageNumbers] ?: e.pageNumbers,
            ),
            trashRetentionDays = p[Keys.trashRetentionDays]?.takeIf { it > 0 } ?: d.trashRetentionDays,
            appLock = AppLockSettings(
                enabled = p[Keys.appLockEnabled] ?: d.appLock.enabled,
                relockAfterSeconds = p[Keys.appLockRelockAfter]?.coerceAtLeast(0) ?: d.appLock.relockAfterSeconds,
            ),
            secureWindow = p[Keys.secureWindow] ?: d.secureWindow,
            onboardingSeen = p[Keys.onboardingSeen] ?: d.onboardingSeen,
        )
    }

    private fun write(p: MutablePreferences, s: Settings) {
        p[Keys.theme] = s.theme.name
        val script = s.ocrScript
        if (script == null) p.remove(Keys.ocrScript) else p[Keys.ocrScript] = script.name
        p[Keys.autoText] = s.autoText
        p[Keys.defaultFilter] = s.defaultFilter.name
        p[Keys.exportFormat] = s.exportDefaults.format.name
        p[Keys.exportSize] = s.exportDefaults.size.name
        p[Keys.exportPageSize] = s.exportDefaults.pageSize.name
        p[Keys.exportMargin] = s.exportDefaults.margin.name
        p[Keys.exportSearchable] = s.exportDefaults.searchable
        p[Keys.exportPageNumbers] = s.exportDefaults.pageNumbers
        p[Keys.trashRetentionDays] = s.trashRetentionDays.coerceAtLeast(1)
        p[Keys.appLockEnabled] = s.appLock.enabled
        p[Keys.appLockRelockAfter] = s.appLock.relockAfterSeconds.coerceAtLeast(0)
        p[Keys.secureWindow] = s.secureWindow
        p[Keys.onboardingSeen] = s.onboardingSeen
    }

    private companion object {
        const val TAG = "settings"
    }
}
