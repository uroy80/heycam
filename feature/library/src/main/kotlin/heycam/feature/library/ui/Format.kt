package heycam.feature.library.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import heycam.feature.library.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Locale-aware dates and sizes for rows, the dateline and TalkBack sentences. */
internal class LibraryFormat(
    private val context: Context,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = context.resources.configuration.locales[0],
) {
    private fun pattern(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private val dayShort = pattern("EEEdMMM")
    private val dayYear = pattern("dMMMyyyy")
    private val dayLong = pattern("EEEEdMMMM")
    private val dayLongYear = pattern("EEEEdMMMMyyyy")
    private val time = pattern(if (DateFormat.is24HourFormat(context)) "Hm" else "hm")

    /** "Tue 20 Oct" for the dateline. */
    fun dateline(today: LocalDate): String = dayShort.format(today)

    /** "Tuesday 20 October": the dateline's date for TalkBack. */
    fun datelineSpoken(today: LocalDate): String = dayLong.format(today)

    /** "Today 14:32", "Mon 19 Oct · 18:05" or "3 Mar 2025" (older than this year). */
    fun stamp(at: Instant, today: LocalDate): String {
        val dt = at.atZone(zone)
        val day = dt.toLocalDate()
        return when {
            day == today -> context.getString(R.string.library_today_at, time.format(dt))
            day == today.minusDays(1) -> context.getString(R.string.library_yesterday_at, time.format(dt))
            day.year == today.year -> context.getString(R.string.library_day_at, dayShort.format(dt), time.format(dt))
            else -> dayYear.format(dt)
        }
    }

    /** "Tuesday 20 October, 14:32" for TalkBack. */
    fun spoken(at: Instant, today: LocalDate): String {
        val dt = at.atZone(zone)
        val day = if (dt.year == today.year) dayLong.format(dt) else dayLongYear.format(dt)
        return context.getString(R.string.library_spoken_date, day, time.format(dt))
    }

    fun day(at: Instant): String = dayShort.format(at.atZone(zone))

    fun size(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun pages(count: Int): String = context.resources.getQuantityString(R.plurals.library_pages, count, count)
}
