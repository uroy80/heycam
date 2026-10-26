package heycam.core.model

enum class ThemeSetting { SYSTEM, DAY_PAPER, NIGHT_INK }

data class Settings(
    val theme: ThemeSetting = ThemeSetting.SYSTEM,
    val ocrScript: OcrScript? = null,          // null = Auto (from device languages)
    val autoText: Boolean = true,              // read text right after saving
    val defaultFilter: PageFilter = PageFilter.ORIGINAL,
    val exportDefaults: ExportOptions = ExportOptions(),
    val trashRetentionDays: Int = 30,
    val appLock: AppLockSettings = AppLockSettings(), // v1.1
    val secureWindow: Boolean = false,         // v1.1: FLAG_SECURE (hide in Recents, block screenshots)
    val onboardingSeen: Boolean = false,
)

data class AppLockSettings(val enabled: Boolean = false, val relockAfterSeconds: Int = 0)
