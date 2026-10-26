package heycam.app

import heycam.core.common.ApplicationScope
import heycam.core.common.Clock
import heycam.core.common.HeycamLog
import heycam.core.data.DocumentRepository
import heycam.core.data.FileStore
import heycam.core.data.SettingsRepository
import heycam.core.domain.PageProcessor
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Work that runs once per process start: resume unfinished page work (PLAN §3.7), sweep orphaned files and stale
 * staging and exports (§3.6), and empty trash older than the retention setting.
 */
class AppStartup @Inject constructor(
    private val processor: PageProcessor,
    private val fileStore: FileStore,
    private val documents: DocumentRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val log: HeycamLog,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun run() {
        processor.kick()
        scope.launch { attempt("sweep") { fileStore.sweep() } }
        scope.launch {
            attempt("trash purge") {
                val retention = Duration.ofDays(settings.settings.first().trashRetentionDays.toLong())
                documents.purgeTrash(trashedBefore = clock.now().minus(retention))
            }
        }
    }

    /** Startup chores must never take the app down; a failure is retried at the next start. */
    private suspend fun attempt(step: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.w(TAG, "Startup $step failed: ${error.javaClass.simpleName}") // class only: messages may hold paths (P3)
        }
    }

    private companion object {
        const val TAG = "AppStartup"
    }
}
