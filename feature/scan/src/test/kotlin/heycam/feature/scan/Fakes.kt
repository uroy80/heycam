package heycam.feature.scan

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import heycam.core.data.ScanSessionStore
import heycam.core.data.SettingsRepository
import heycam.core.domain.CommitResult
import heycam.core.domain.ScanImporter
import heycam.core.domain.StageResult
import heycam.core.imaging.PageRenderer
import heycam.core.imaging.RenderSpec
import heycam.core.model.DocumentId
import heycam.core.model.ImageInfo
import heycam.core.model.ImportTarget
import heycam.core.model.ModuleState
import heycam.core.model.PageEdits
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.model.Settings
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import heycam.core.scanner.ScanRequest
import heycam.core.scanner.ScanResult
import heycam.core.scanner.ScanStart
import heycam.core.scanner.ScannerLauncher
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

fun stagedImage(name: String, origin: PageOrigin = PageOrigin.SCANNER, w: Int = 2480, h: Int = 3508) =
    StagedImage(File("/staging/$name.jpg"), w, h, "image/jpeg", origin)

class FakeScannerLauncher : ScannerLauncher {
    var availability: ModuleState = ModuleState.Ready
    var start: ScanStart = ScanStart.Immediate(ScanResult.Pages(listOf(stagedImage("scan1"))))
    var finish: ScanResult = ScanResult.Cancelled
    var installStates: List<ModuleState> = listOf(ModuleState.Downloading(50, 100), ModuleState.Ready)
    val requests = mutableListOf<ScanRequest>()
    var installs = 0

    override suspend fun availability(): ModuleState = availability
    override fun install(): Flow<ModuleState> {
        installs++
        return flowOf(*installStates.toTypedArray())
    }
    override suspend fun start(activity: Activity, request: ScanRequest): ScanStart {
        requests += request
        return start
    }
    override suspend fun finish(resultCode: Int, data: Intent?): ScanResult = finish
}

class FakeScanImporter : ScanImporter {
    var stageResult = StageResult(listOf(stagedImage("photo1", PageOrigin.PHOTO_PICKER)), rejected = 0)
    var failStage = false
    var failCommit = false
    val staged = mutableListOf<Pair<List<Uri>, PageOrigin>>()
    val committed = mutableListOf<ScanSession>()
    /** When set, a commit waits for it before it writes anything, like a slow file move. */
    var commitGate: CompletableDeferred<Unit>? = null

    override suspend fun stage(uris: List<Uri>, origin: PageOrigin): StageResult {
        if (failStage) error("stage failed")
        staged += uris to origin
        return stageResult
    }

    override suspend fun commit(session: ScanSession): CommitResult {
        commitGate?.await()
        if (failCommit) error("commit failed")
        committed += session
        return CommitResult(DocumentId(42), session.pages.indices.map { PageId(100L + it) })
    }
}

class FakeScanSessionStore : ScanSessionStore {
    val sessions = linkedMapOf<String, ScanSession>()
    val discarded = mutableListOf<String>()
    var updates = 0
    private var next = 1

    override suspend fun create(target: ImportTarget, images: List<StagedImage>): ScanSession {
        val session = ScanSession("s${next++}", target, images.map { StagedPage(it) }, Instant.EPOCH)
        sessions[session.id] = session
        return session
    }

    override suspend fun get(id: String): ScanSession? = sessions[id]

    override suspend fun update(session: ScanSession) {
        updates++
        sessions[session.id] = session
    }

    override suspend fun discard(id: String) {
        discarded += id
        sessions.remove(id)
    }
}

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state
    override suspend fun update(transform: (Settings) -> Settings) { state.value = transform(state.value) }
}

/** Previews are tiny blank bitmaps; renders copy nothing (Review never renders to disk). */
class FakePageRenderer : PageRenderer {
    val previews = mutableListOf<Pair<String, PageEdits>>()

    override suspend fun render(source: ImageInfo, edits: PageEdits, spec: RenderSpec, out: File): ImageInfo = source

    override suspend fun preview(source: ImageInfo, edits: PageEdits, maxLongEdgePx: Int): Bitmap {
        previews += source.path to edits
        return Bitmap.createBitmap(8, 10, Bitmap.Config.ARGB_8888)
    }

    override suspend fun thumbnail(image: ImageInfo, out: File, maxLongEdgePx: Int): File = out
}

/** A page-like picture for previews: paper with a title bar and ruled lines, [w] x [h] pixels. */
fun pagePicture(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
    eraseColor(0xFFFBF8F1.toInt())
    val canvas = Canvas(this)
    val ink = Paint().apply { color = 0xFF141210.toInt() }
    canvas.drawRect(w * 0.1f, h * 0.08f, w * 0.7f, h * 0.14f, ink)
    var y = h * 0.24f
    while (y < h * 0.9f) {
        canvas.drawRect(w * 0.1f, y, w * 0.9f, y + maxOf(1f, h * 0.01f), ink)
        y += h * 0.08f
    }
}
