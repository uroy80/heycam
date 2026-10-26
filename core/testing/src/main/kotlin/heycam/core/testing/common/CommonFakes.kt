package heycam.core.testing.common

import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import java.time.Duration
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** A [Clock] the test moves by hand. Starts at 2026-10-20 09:00 UTC. */
class TestClock(var now: Instant = Instant.parse("2026-10-20T09:00:00Z")) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** Every dispatcher is [dispatcher]; pass the test's own (e.g. `StandardTestDispatcher(testScheduler)`) to control time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatcherProvider(val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()) : DispatcherProvider {
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val main: CoroutineDispatcher get() = dispatcher
}

/** Swaps Dispatchers.Main for [dispatcher] around each test (ViewModel tests). */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Keeps every log line so tests can check what was logged and, just as important, what was not (P3). */
class RecordingLog : HeycamLog {
    data class Entry(val level: Char, val tag: String, val message: String, val error: Throwable?)

    private val _entries = Collections.synchronizedList(mutableListOf<Entry>())
    val entries: List<Entry> get() = synchronized(_entries) { _entries.toList() }

    override fun d(tag: String, message: String) {
        _entries += Entry('D', tag, message, null)
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        _entries += Entry('W', tag, message, error)
    }

    override fun e(tag: String, message: String, error: Throwable?) {
        _entries += Entry('E', tag, message, error)
    }
}
