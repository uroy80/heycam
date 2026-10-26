package heycam.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** What an entry point asked MainActivity for: a launcher shortcut, the Quick Settings tile or a share from another app. */
sealed interface LaunchAction {
    data object Scan : LaunchAction
    data object Import : LaunchAction
    data object Search : LaunchAction

    /**
     * Images shared into heycam (PLAN P7): content:// URIs only, at most [MAX_SHARED_IMAGES]. [skipped] counts the
     * rest, such as file:// URIs, which heycam never reads.
     */
    data class Share(val images: List<Uri>, val skipped: Int) : LaunchAction

    companion object {
        const val ACTION_SCAN = "heycam.action.SCAN"
        const val ACTION_IMPORT = "heycam.action.IMPORT"
        const val ACTION_SEARCH = "heycam.action.SEARCH"
        const val MAX_SHARED_IMAGES = 100

        /**
         * Null for a plain launch, an intent heycam does not handle, or a task reopened from Recents: there the system
         * hands back the intent that started the task, and a shortcut, the tile or a share would run a second time.
         */
        fun from(intent: Intent?): LaunchAction? {
            if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return when (intent.action) {
                ACTION_SCAN -> Scan
                ACTION_IMPORT -> Import
                ACTION_SEARCH -> Search
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> share(intent)
                else -> null
            }
        }

        /**
         * Any app can send a share, so its extras are untrusted: a null or non-Uri entry, a single Uri where a list
         * belongs, or a Parcelable class heycam cannot load (Android 12 and older then throw on reading any extra)
         * counts as nothing shared instead of crashing heycam.
         */
        private fun share(intent: Intent): Share? {
            val streams: List<Any?> = runCatching {
                if (intent.action == Intent.ACTION_SEND) {
                    listOf(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                } else {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                }
            }.getOrDefault(emptyList())
            val clip: List<Any?> = runCatching {
                intent.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }.orEmpty()
            }.getOrDefault(emptyList())
            val shared = (streams + clip).filterIsInstance<Uri>().distinct()
            if (shared.isEmpty()) return null
            val images = shared.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.take(MAX_SHARED_IMAGES)
            return Share(images = images, skipped = shared.size - images.size)
        }
    }
}
