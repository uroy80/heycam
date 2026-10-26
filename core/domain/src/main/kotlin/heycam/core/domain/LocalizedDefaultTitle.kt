package heycam.core.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

/**
 * "Scan 20 Oct 2026, 14:32": the word from the app's resources (the UI language) and the date and time in
 * [Locale]'s own style.
 */
class LocalizedDefaultTitle @Inject constructor(@ApplicationContext private val context: Context) : DefaultTitle {
    override fun forNewDocument(at: Instant, zone: ZoneId, locale: Locale): String {
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        return context.getString(R.string.domain_default_title, formatter.format(at.atZone(zone)))
    }
}
