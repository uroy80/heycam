package heycam.core.common

import android.util.Log

/**
 * [HeycamLog] on logcat. This file is the one place in heycam allowed to import android.util.Log.
 * Debug lines are dropped unless the app is debuggable; callers never pass content (P3).
 */
class AndroidHeycamLog(private val debugEnabled: Boolean) : HeycamLog {
    override fun d(tag: String, message: String) {
        if (debugEnabled) Log.d(prefixed(tag), message)
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        Log.w(prefixed(tag), withErrorClass(message, error))
    }

    override fun e(tag: String, message: String, error: Throwable?) {
        Log.e(prefixed(tag), withErrorClass(message, error))
    }

    // Exception messages can carry file names or URIs, so only the class name reaches the log.
    private fun withErrorClass(message: String, error: Throwable?): String =
        if (error == null) message else "$message (${error.javaClass.name})"

    private fun prefixed(tag: String) = "heycam.$tag"
}
