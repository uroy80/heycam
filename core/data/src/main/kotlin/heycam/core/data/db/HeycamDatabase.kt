package heycam.core.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.CoroutineContext

/**
 * heycam.db on the bundled SQLite (the platform SQLite has no FTS5). Schema JSON lives in core/data/schemas;
 * every version change needs a Migration and a migration test.
 */
@Database(
    entities = [FolderEntity::class, DocumentEntity::class, PageEntity::class, PageTextEntity::class, PageTextFts::class],
    version = 1,
    exportSchema = true,
)
internal abstract class HeycamDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun pageTextDao(): PageTextDao

    companion object {
        const val NAME = "heycam.db"

        fun build(context: Context, queryContext: CoroutineContext): HeycamDatabase =
            Room.databaseBuilder<HeycamDatabase>(context, context.getDatabasePath(NAME).absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(queryContext)
                .build()
    }
}
