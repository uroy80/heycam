package heycam.core.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.data.db.HeycamDatabase
import heycam.core.model.PageEdits
import heycam.core.model.PageOrigin
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal object TestDispatchers : DispatcherProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
}

/** Starts at the real time (file ages in tests are compared with real modification times), whole milliseconds. */
internal class MutableClock(var now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)) : Clock {
    override fun now(): Instant = now
    fun advance(by: Duration) { now = now.plus(by) }
}

internal class RecordingLog : HeycamLog {
    val lines = mutableListOf<String>()
    override fun d(tag: String, message: String) { lines += "D/$tag: $message" }
    override fun w(tag: String, message: String, error: Throwable?) { lines += "W/$tag: $message" }
    override fun e(tag: String, message: String, error: Throwable?) { lines += "E/$tag: $message" }
}

/**
 * The bundled driver with a hook that runs right after a statement steps without error, so a test can act at an
 * exact point of a transaction (for example cancel a save the moment Room commits it).
 */
internal class HookedDriver(private val delegate: SQLiteDriver = BundledSQLiteDriver()) : SQLiteDriver {
    /** Called with the statement's SQL on the database thread; null = no hook. */
    @Volatile var afterStep: ((sql: String) -> Unit)? = null

    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool

    override fun open(fileName: String): SQLiteConnection = HookedConnection(delegate.open(fileName))

    private inner class HookedConnection(private val connection: SQLiteConnection) : SQLiteConnection by connection {
        override fun inTransaction(): Boolean = connection.inTransaction()
        override fun prepare(sql: String): SQLiteStatement = HookedStatement(sql, connection.prepare(sql))
    }

    private inner class HookedStatement(private val sql: String, private val statement: SQLiteStatement) : SQLiteStatement by statement {
        override fun step(): Boolean = statement.step().also { afterStep?.invoke(sql) }
    }
}

/** The real :core:data classes over an in-memory bundled-SQLite database and a temporary filesDir/cacheDir. */
internal class DataTestRig(root: File) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val dirs = StorageDirs(File(root, "files"), File(root, "cache"))
    val clock = MutableClock()
    val log = RecordingLog()
    val lock = StorageLock()
    val driver = HookedDriver()
    val db: HeycamDatabase = Room.inMemoryDatabaseBuilder<HeycamDatabase>(context)
        .setDriver(driver)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    val files = DiskFileStore(dirs, db, lock, clock, TestDispatchers, log)
    val documents = RoomDocumentRepository(db, files, lock, clock, TestDispatchers, log)
    val folders = RoomFolderRepository(db, clock, TestDispatchers)

    private var counter = 0

    /** A staged JPEG-named file in loose staging, as the importer or scanner leaves it. */
    fun staged(width: Int = 100, height: Int = 150, bytes: Int = 1000, edits: PageEdits = PageEdits()): StagedPage {
        val file = files.newStagedFile("jpg")
        file.writeBytes(ByteArray(bytes) { (counter + it).toByte() })
        counter++
        return StagedPage(StagedImage(file, width, height, "image/jpeg", PageOrigin.SCANNER), edits)
    }

    fun close() = db.close()
}
