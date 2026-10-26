package heycam.core.model

/** State of a Google Play services module (document scanner UI, text-recognition models). */
sealed interface ModuleState {
    data object Ready : ModuleState
    data object Checking : ModuleState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long?) : ModuleState
    data object Installing : ModuleState
    data class Failed(val reason: ModuleFailure, val retryable: Boolean) : ModuleState
    data class Unsupported(val reason: UnsupportedReason) : ModuleState
}

enum class ModuleFailure { NO_NETWORK, CANCELLED, UNKNOWN }

enum class UnsupportedReason { NO_PLAY_SERVICES, PLAY_SERVICES_OUTDATED, LOW_RAM }
