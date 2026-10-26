package heycam.core.common

import java.io.File
import java.time.Instant
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher

/** Process-lifetime scope (SupervisorJob + Dispatchers.Default) for work that must outlive a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

fun interface Clock { fun now(): Instant }

/** Implemented by :core:data FileStore. Files land in filesDir/staging (same volume as pages, so commit is a rename). */
fun interface StagingArea { fun newStagedFile(extension: String): File }

/**
 * The only logger heycam uses. Release builds keep warn/error only. NEVER pass document titles, file names,
 * recognised text, URIs or passwords; pass ids, counts, durations and error classes.
 */
interface HeycamLog {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}
