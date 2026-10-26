package heycam.core.common

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Test

class ApplicationScopeTest {
    private class Recording : HeycamLog {
        val errors = CopyOnWriteArrayList<Throwable?>()
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, error: Throwable?) = Unit
        override fun e(tag: String, message: String, error: Throwable?) {
            errors += error
        }
    }

    private object Unconfined : DispatcherProvider {
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    @Test
    fun aFailedBackgroundJobIsLoggedAndTheScopeKeepsWorking() {
        val log = Recording()
        val scope = CommonModule.applicationScope(Unconfined, log)
        scope.launch { throw IllegalStateException("render failed") }
        val ran = CountDownLatch(1)
        scope.launch { ran.countDown() }
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(log.errors.single()).isInstanceOf(IllegalStateException::class.java)
        scope.cancel()
    }
}
