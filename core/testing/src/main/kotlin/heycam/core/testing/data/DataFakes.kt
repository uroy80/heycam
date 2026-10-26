package heycam.core.testing.data

import heycam.core.common.Clock
import heycam.core.data.FileStore
import heycam.core.data.ScanSessionStore
import heycam.core.data.SettingsRepository
import heycam.core.data.StorageUsage
import heycam.core.data.SweepReport
import heycam.core.model.ImportTarget
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.ScanSession
import heycam.core.model.Settings
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import heycam.core.testing.common.TestClock
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = state

    /** The latest value, for assertions. */
    val current: Settings get() = state.value

    override suspend fun update(transform: (Settings) -> Settings) = state.update(transform)
}

/**
 * In-memory [ScanSessionStore]: new sessions start every page on [defaultFilter] (Settings.defaultFilter in the app);
 * staged files are left where they are. [discarded] lists every discarded id.
 */
class FakeScanSessionStore(
    private val clock: Clock = TestClock(),
    var defaultFilter: PageFilter = PageFilter.ORIGINAL,
) : ScanSessionStore {
    private val sessions = MutableStateFlow<Map<String, ScanSession>>(emptyMap())
    val discarded = mutableListOf<String>()

    /** Every stored session, for assertions. */
    val all: Map<String, ScanSession> get() = sessions.value

    override suspend fun create(target: ImportTarget, images: List<StagedImage>): ScanSession {
        val session = ScanSession(UUID.randomUUID().toString(), target, images.map { StagedPage(it, PageEdits(filter = defaultFilter)) }, clock.now())
        sessions.update { it + (session.id to session) }
        return session
    }

    override suspend fun get(id: String): ScanSession? = sessions.value[id]

    override suspend fun update(session: ScanSession) {
        sessions.update { all ->
            require(session.id in all) { "unknown session" }
            all + (session.id to session)
        }
    }

    override suspend fun discard(id: String) {
        sessions.update { it - id }
        discarded += id
    }
}

/**
 * [FileStore] (and so StagingArea) over a temporary [root], laid out like the real one: pages/, staging/loose/ and
 * exports/. [sweep] and [clearTemporaryFiles] count calls and return [sweepReport]; [usage] measures the folders
 * unless [fixedUsage] is set.
 */
class FakeFileStore(val root: File) : FileStore {
    var sweepReport = SweepReport(filesDeleted = 0, bytesFreed = 0)
    var fixedUsage: StorageUsage? = null
    var sweeps = 0
        private set
    var clears = 0
        private set

    override fun newStagedFile(extension: String): File =
        File(File(root, "staging/loose").apply { mkdirs() }, "${UUID.randomUUID()}.${extension.removePrefix(".")}")

    override fun pageDir(fileKey: String): File = File(root, "pages/$fileKey")

    override fun exportDir(): File = File(root, "exports").apply { mkdirs() }

    override suspend fun sweep(): SweepReport {
        sweeps++
        return sweepReport
    }

    override suspend fun clearTemporaryFiles(): SweepReport {
        clears++
        return sweepReport
    }

    override suspend fun usage(): StorageUsage = fixedUsage ?: StorageUsage(
        pagesBytes = sizeOf(File(root, "pages")),
        trashBytes = 0,
        cacheBytes = sizeOf(File(root, "staging")) + sizeOf(File(root, "exports")),
    )

    private fun sizeOf(dir: File) = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
