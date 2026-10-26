package heycam.core.domain

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalizedDefaultTitleTest {
    private val title = LocalizedDefaultTitle(ApplicationProvider.getApplicationContext())
    private val at = Instant.parse("2026-10-20T09:02:00Z") // 14:32 in India

    @Test
    fun britishEnglishReadsLikeThePlan() {
        assertThat(title.forNewDocument(at, ZoneId.of("Asia/Kolkata"), Locale.UK)).isEqualTo("Scan 20 Oct 2026, 14:32")
    }

    @Test
    fun followsTheLocalesDateStyleAndTheZone() {
        val us = title.forNewDocument(at, ZoneId.of("America/New_York"), Locale.US)
        assertThat(us).startsWith("Scan Oct 20, 2026, 5:02")
        assertThat(us).endsWith("AM")
    }
}
