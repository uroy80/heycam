package heycam.feature.document.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Polite TalkBack lines for actions that show nothing else for themselves, such as moving or turning a page from
 * its TalkBack actions. Shown by [Announcement].
 */
@Stable
internal class Announcer {
    /** What the live region says now; empty when there is nothing to say. */
    var line by mutableStateOf("")
        private set

    private val requests = Channel<String>(Channel.CONFLATED)

    fun say(text: String) {
        requests.trySend(text)
    }

    /**
     * Speaks each request. A repeat of the line still showing is cleared first so TalkBack hears it again, and a line
     * is cleared after a while so it does not linger as an extra stop in TalkBack's swipe order.
     */
    suspend fun run() {
        var next: String? = null
        while (true) {
            val text = next ?: requests.receive()
            if (text == line) {
                line = ""
                delay(REPEAT_GAP_MS)
            }
            line = text
            next = withTimeoutOrNull(HOLD_MS) { requests.receive() }
            if (next == null) line = ""
        }
    }

    private companion object {
        /** Longer than Compose's batching of accessibility events, so the clear and the repeat are two changes. */
        const val REPEAT_GAP_MS = 250L
        const val HOLD_MS = 5_000L
    }
}

/** The live region [announcer] speaks through: an invisible 1 dp node. */
@Composable
internal fun Announcement(announcer: Announcer, modifier: Modifier = Modifier) {
    LaunchedEffect(announcer) { announcer.run() }
    val line = announcer.line
    Box(
        modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            if (line.isNotEmpty()) contentDescription = line
        },
    )
}
