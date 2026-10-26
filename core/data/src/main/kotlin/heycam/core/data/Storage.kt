package heycam.core.data

import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** On-disk layout (PLAN 3.6). Pages and staging share filesDir, so committing a staged image is a rename. */
internal class StorageDirs(filesDir: File, cacheDir: File) {
    val pages = File(filesDir, "pages")
    val staging = File(filesDir, "staging")
    val looseStaging = File(staging, "loose")
    val exports = File(cacheDir, "exports")
    val mlkitCache = File(cacheDir, "mlkit_docscan_ui_client")   // ML Kit's scanner never cleans this up
}

/**
 * Serialises everything that creates or deletes page and staging files, so a sweep never removes a directory that a
 * commit is filling. Not reentrant: a holder must not call another locking function.
 */
@Singleton
internal class StorageLock @Inject constructor() {
    private val mutex = Mutex()
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

internal object PageFiles {
    private const val SOURCE_JPEG = "source.jpg"
    private const val SOURCE_PNG = "source.png"
    private val FILE_KEY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun sourceNameFor(mime: String): String = if (mime == "image/png") SOURCE_PNG else SOURCE_JPEG

    fun mimeOf(fileName: String): String = if (fileName.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"

    fun isFileKey(value: String): Boolean = FILE_KEY.matches(value)
}

internal data class Freed(val files: Int = 0, val bytes: Long = 0) {
    operator fun plus(other: Freed) = Freed(files + other.files, bytes + other.bytes)
}

internal object FileOps {
    /** Rename, falling back to copy + delete when the two paths are on different volumes. */
    fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        if (!source.delete()) throw IOException("could not remove the moved file")
    }

    fun sizeOf(file: File): Long = file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Deletes a file or a whole tree; counts only the files that were really removed. */
    fun delete(file: File): Freed {
        var freed = Freed()
        file.walkBottomUp().forEach { f ->
            if (f.isFile) {
                val bytes = f.length()
                if (f.delete()) freed += Freed(1, bytes)
            } else {
                f.delete()
            }
        }
        return freed
    }

    /** Newest modification time in a tree (a directory's own time does not change when a nested file does). */
    fun newestModified(file: File): Long = file.walkBottomUp().maxOfOrNull { it.lastModified() } ?: file.lastModified()

    fun isInside(root: File, file: File): Boolean {
        val rootPath = root.canonicalFile.path + File.separator
        return file.canonicalFile.path.startsWith(rootPath)
    }
}
