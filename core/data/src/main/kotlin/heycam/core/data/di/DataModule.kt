package heycam.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import heycam.core.common.ApplicationScope
import heycam.core.common.DispatcherProvider
import heycam.core.common.StagingArea
import heycam.core.data.DataStoreSettingsRepository
import heycam.core.data.DiskFileStore
import heycam.core.data.DocumentRepository
import heycam.core.data.FileScanSessionStore
import heycam.core.data.FileStore
import heycam.core.data.FolderRepository
import heycam.core.data.PageTextEditor
import heycam.core.data.PageWorkStore
import heycam.core.data.RoomDocumentRepository
import heycam.core.data.RoomFolderRepository
import heycam.core.data.ScanSessionStore
import heycam.core.data.SettingsRepository
import heycam.core.data.StorageDirs
import heycam.core.data.db.HeycamDatabase
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataBindings {
    @Binds abstract fun documents(impl: RoomDocumentRepository): DocumentRepository
    @Binds abstract fun pageWork(impl: RoomDocumentRepository): PageWorkStore
    @Binds abstract fun textEditor(impl: RoomDocumentRepository): PageTextEditor
    @Binds abstract fun folders(impl: RoomFolderRepository): FolderRepository
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun sessions(impl: FileScanSessionStore): ScanSessionStore
    @Binds abstract fun files(impl: DiskFileStore): FileStore
    @Binds abstract fun staging(impl: DiskFileStore): StagingArea
}

@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    private const val SETTINGS_FILE = "settings"

    @Provides
    @Singleton
    fun storageDirs(@ApplicationContext context: Context): StorageDirs = StorageDirs(context.filesDir, context.cacheDir)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, dispatchers: DispatcherProvider): HeycamDatabase =
        HeycamDatabase.build(context, dispatchers.io)

    /**
     * An unreadable settings file falls back to defaults instead of crashing the app.
     *
     * DataStore allows one instance per file per process. The app has one SingletonComponent, but Hilt device tests
     * build a new one for every test in the same process, so the instance is kept for the process here.
     */
    @Provides
    @Singleton
    fun settingsStore(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<Preferences> = processSettingsStore ?: synchronized(this) {
        processSettingsStore ?: PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope + dispatchers.io,
            produceFile = { context.preferencesDataStoreFile(SETTINGS_FILE) },
        ).also { processSettingsStore = it }
    }

    @Volatile private var processSettingsStore: DataStore<Preferences>? = null
}
