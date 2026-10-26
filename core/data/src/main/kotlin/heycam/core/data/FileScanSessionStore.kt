package heycam.core.data

import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.model.DocumentId
import heycam.core.model.FolderId
import heycam.core.model.ImportTarget
import heycam.core.model.PageEdits
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Singleton
internal class FileScanSessionStore @Inject constructor(
    private val dirs: StorageDirs,
    private val settings: SettingsRepository,
    private val lock: StorageLock,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val log: HeycamLog,
) : ScanSessionStore {

    override suspend fun create(target: ImportTarget, images: List<StagedImage>): ScanSession {
        val filter = settings.settings.first().defaultFilter
        return withContext(dispatchers.io) {
            lock.withLock {
                val id = UUID.randomUUID().toString()
                val dir = File(dirs.staging, id).apply { mkdirs() }
                val pages = images.map { image ->
                    val file = if (FileOps.isInside(dirs.looseStaging, image.file)) {
                        File(dir, image.file.name).also { FileOps.move(image.file, it) }
                    } else {
                        image.file
                    }
                    StagedPage(image.copy(file = file), PageEdits(filter = filter))
                }
                ScanSession(id, target, pages, clock.now()).also { write(it) }
            }
        }
    }

    override suspend fun get(id: String): ScanSession? = withContext(dispatchers.io) {
        if (!SessionFiles.isSessionId(id)) return@withContext null
        lock.withLock {
            val json = SessionFiles.jsonOf(dirs, id)
            val stored = SessionFiles.load(dirs, json) ?: return@withLock null
            json.setLastModified(clock.now().toEpochMilli())   // an open session counts as live for the sweep
            stored.copy(pages = stored.pages.filter { page -> page.image.file.isFile })
        }
    }

    override suspend fun update(session: ScanSession) = withContext(dispatchers.io) {
        require(SessionFiles.isSessionId(session.id)) { "not a session id" }
        lock.withLock {
            val previous = SessionFiles.load(dirs, SessionFiles.jsonOf(dirs, session.id))
            val kept = session.pages.map { it.image.file.canonicalFile }.toSet()
            previous?.pages
                ?.map { it.image.file }
                ?.filter { it.canonicalFile !in kept && FileOps.isInside(dirs.staging, it) }
                ?.forEach { FileOps.delete(it) }
            write(session)
        }
    }

    override suspend fun discard(id: String) = withContext(dispatchers.io) {
        if (!SessionFiles.isSessionId(id)) return@withContext
        lock.withLock {
            SessionFiles.load(dirs, SessionFiles.jsonOf(dirs, id))?.pages
                ?.map { it.image.file }
                ?.filter { FileOps.isInside(dirs.staging, it) }
                ?.forEach { FileOps.delete(it) }
            FileOps.delete(File(dirs.staging, id))
        }
        Unit
    }

    private fun write(session: ScanSession) {
        val json = SessionFiles.jsonOf(dirs, session.id)
        json.parentFile?.mkdirs()
        val tmp = File(json.parentFile, "${json.name}.tmp")
        tmp.writeText(StorageJson.encodeToString(SessionJson.serializer(), SessionJson.of(session, dirs)))
        if (!tmp.renameTo(json)) {
            tmp.delete()
            log.w(TAG, "could not save session ${session.id}")
            throw IOException("could not save the session")
        }
    }

    private companion object { const val TAG = "sessions" }
}

/** session.json layout and the helpers the sweep shares with the store. */
internal object SessionFiles {
    const val FILE = "session.json"
    private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun isSessionId(id: String) = ID.matches(id)

    fun jsonOf(dirs: StorageDirs, id: String) = File(File(dirs.staging, id), FILE)

    /** Null when the file is missing or unreadable (a damaged session is treated as gone). */
    fun load(dirs: StorageDirs, json: File): ScanSession? =
        if (!json.isFile) null else runCatching { StorageJson.decodeFromString(SessionJson.serializer(), json.readText()).toSession(dirs) }.getOrNull()

    /** Every image file a session refers to (absolute). */
    fun referencedFiles(dirs: StorageDirs, json: File): List<File> = load(dirs, json)?.pages?.map { it.image.file }.orEmpty()

    fun relativize(dirs: StorageDirs, file: File): String =
        if (FileOps.isInside(dirs.staging, file)) file.canonicalFile.relativeTo(dirs.staging.canonicalFile).path else file.absolutePath

    fun resolve(dirs: StorageDirs, path: String): File = File(path).let { if (it.isAbsolute) it else File(dirs.staging, path) }
}

@Serializable
internal data class SessionJson(
    val version: Int = 1,
    val id: String,
    val createdAt: Long,
    val target: TargetJson,
    val pages: List<StagedPageJson>,
) {
    fun toSession(dirs: StorageDirs) = ScanSession(
        id = id,
        target = target.toTarget(),
        pages = pages.map { p ->
            StagedPage(
                image = StagedImage(SessionFiles.resolve(dirs, p.path), p.width, p.height, p.mime, enumOr(p.origin, PageOrigin.SCANNER)),
                edits = p.edits.toEdits(),
            )
        },
        createdAt = Instant.ofEpochMilli(createdAt),
    )

    companion object {
        fun of(session: ScanSession, dirs: StorageDirs) = SessionJson(
            id = session.id,
            createdAt = session.createdAt.toEpochMilli(),
            target = TargetJson.of(session.target),
            pages = session.pages.map { p ->
                StagedPageJson(
                    path = SessionFiles.relativize(dirs, p.image.file),
                    width = p.image.widthPx,
                    height = p.image.heightPx,
                    mime = p.image.mime,
                    origin = p.image.origin.name,
                    edits = EditsJson.of(p.edits),
                )
            },
        )
    }
}

@Serializable
internal data class StagedPageJson(
    val path: String,                 // relative to filesDir/staging, absolute only for files outside it
    val width: Int,
    val height: Int,
    val mime: String,
    val origin: String,
    val edits: EditsJson = EditsJson(),
)

@Serializable
internal data class TargetJson(
    val kind: String,
    val title: String? = null,
    val folderId: Long? = null,
    val documentId: Long? = null,
    val atIndex: Int? = null,
    val pageId: Long? = null,
) {
    fun toTarget(): ImportTarget = when (kind) {
        APPEND -> ImportTarget.AppendTo(DocumentId(requireNotNull(documentId)), atIndex)
        REPLACE -> ImportTarget.Replace(PageId(requireNotNull(pageId)))
        else -> ImportTarget.NewDocument(title, folderId?.let(::FolderId))
    }

    companion object {
        private const val NEW = "new"
        private const val APPEND = "append"
        private const val REPLACE = "replace"

        fun of(target: ImportTarget) = when (target) {
            is ImportTarget.NewDocument -> TargetJson(NEW, title = target.title, folderId = target.folderId?.value)
            is ImportTarget.AppendTo -> TargetJson(APPEND, documentId = target.documentId.value, atIndex = target.atIndex)
            is ImportTarget.Replace -> TargetJson(REPLACE, pageId = target.pageId.value)
        }
    }
}
