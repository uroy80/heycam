package heycam.core.data

import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.data.db.HeycamDatabase
import heycam.core.data.db.PageFileRow
import java.io.File
import java.time.Duration
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * [FileStore] over filesDir/pages, filesDir/staging and cacheDir/exports (PLAN 3.6). Every create/delete of page or
 * staging files runs under [StorageLock], so a sweep never races a commit.
 */
@Singleton
internal class DiskFileStore @Inject constructor(
    private val dirs: StorageDirs,
    private val db: HeycamDatabase,
    private val lock: StorageLock,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val log: HeycamLog,
) : FileStore {

    override fun newStagedFile(extension: String): File {
        val ext = extension.removePrefix(".").lowercase()
        require(EXTENSION.matches(ext)) { "unsupported extension" }
        dirs.looseStaging.mkdirs()
        return File(dirs.looseStaging, "${UUID.randomUUID()}.$ext")
    }

    override fun pageDir(fileKey: String): File {
        require(PageFiles.isFileKey(fileKey)) { "not a file key" }
        return File(dirs.pages, fileKey)
    }

    override fun exportDir(): File = dirs.exports.apply { mkdirs() }

    override suspend fun sweep(): SweepReport = withContext(dispatchers.io) {
        lock.withLock { sweepLocked(cutoff()) }.toReport()
    }

    override suspend fun clearTemporaryFiles(): SweepReport = withContext(dispatchers.io) {
        lock.withLock {
            sweepLocked(cutoff()) + deleteChildren(dirs.exports) { true } + deleteChildren(dirs.mlkitCache) { true }
        }.toReport()
    }

    override suspend fun usage(): StorageUsage = withContext(dispatchers.io) {
        val trashed = db.pageDao().files().filter { it.trashed }.mapTo(HashSet()) { it.fileKey }
        var pages = 0L
        var trash = 0L
        dirs.pages.listFiles()?.forEach { dir ->
            val bytes = FileOps.sizeOf(dir)
            if (dir.name in trashed) trash += bytes else pages += bytes
        }
        val cache = FileOps.sizeOf(dirs.staging) + FileOps.sizeOf(dirs.exports) + FileOps.sizeOf(dirs.mlkitCache)
        StorageUsage(pagesBytes = pages, trashBytes = trash, cacheBytes = cache)
    }

    private fun cutoff(): Long = clock.now().minus(MAX_AGE).toEpochMilli()

    private suspend fun sweepLocked(cutoff: Long): Freed {
        val rows = db.pageDao().files().associateBy(PageFileRow::fileKey)
        val freed = sweepPages(rows, cutoff) + sweepStaging(cutoff) +
            deleteChildren(dirs.exports) { FileOps.newestModified(it) < cutoff } +
            deleteChildren(dirs.mlkitCache) { FileOps.newestModified(it) < cutoff }
        if (freed.files > 0) log.d(TAG, "swept ${freed.files} files, ${freed.bytes} bytes")
        return freed
    }

    /** Directories no page row owns go at once (commits hold the lock); stray files in owned ones once they are old. */
    private fun sweepPages(rows: Map<String, PageFileRow>, cutoff: Long): Freed {
        var freed = Freed()
        dirs.pages.listFiles()?.forEach { dir ->
            val row = rows[dir.name]
            freed += when {
                row == null || !dir.isDirectory -> FileOps.delete(dir)
                else -> deleteChildren(dir) { it.name !in row.names && FileOps.newestModified(it) < cutoff }
            }
        }
        return freed
    }

    /**
     * Session directories stay while anything in them changed within [MAX_AGE] (opening a session touches it). Old
     * loose files go unless a staying session still refers to them (pages added to a session during Review).
     */
    private fun sweepStaging(cutoff: Long): Freed {
        var freed = Freed()
        val keptReferences = HashSet<File>()
        dirs.staging.listFiles()?.filter { it != dirs.looseStaging }?.forEach { child ->
            if (FileOps.newestModified(child) < cutoff) {
                freed += FileOps.delete(child)
            } else if (child.isDirectory) {
                SessionFiles.referencedFiles(dirs, File(child, SessionFiles.FILE)).mapTo(keptReferences) { it.canonicalFile }
            }
        }
        freed += deleteChildren(dirs.looseStaging) { it.lastModified() < cutoff && it.canonicalFile !in keptReferences }
        return freed
    }

    private fun deleteChildren(dir: File, doomed: (File) -> Boolean): Freed =
        dir.listFiles()?.filter(doomed)?.fold(Freed()) { acc, f -> acc + FileOps.delete(f) } ?: Freed()

    private fun Freed.toReport() = SweepReport(filesDeleted = files, bytesFreed = bytes)

    private companion object {
        const val TAG = "files"
        val MAX_AGE: Duration = Duration.ofHours(24)
        val EXTENSION = Regex("[a-z0-9]{1,5}")
    }
}
