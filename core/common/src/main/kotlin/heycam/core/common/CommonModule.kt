package heycam.core.common

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Production dispatchers: Room on IO, imaging on Default, UI on Main. */
object DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}

/** Wall-clock time. */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

@Module
@InstallIn(SingletonComponent::class)
object CommonModule {
    @Provides
    fun dispatchers(): DispatcherProvider = DefaultDispatcherProvider

    @Provides
    fun clock(): Clock = SystemClock

    /**
     * Background work that outlives a screen (the page processor, printing, sweeps). A failure in one job is
     * logged and ends only that job; the SupervisorJob keeps the others, and the app, running.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(dispatchers: DispatcherProvider, log: HeycamLog): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + dispatchers.default + CoroutineExceptionHandler { _, e -> log.e("AppScope", "Background job failed", e) },
        )

    @Provides
    @Singleton
    fun log(@ApplicationContext context: Context): HeycamLog =
        AndroidHeycamLog(debugEnabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
}
