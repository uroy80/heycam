package heycam.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.compose.material3.R as Material3R

/**
 * heycam's strings are English only, so the app ships only English library strings too (localeFilters). Otherwise a
 * Hindi phone hears Compose's own labels in Hindi among heycam's English ones, and a right-to-left language first in
 * the list lays the English screens out right to left.
 */
@RunWith(AndroidJUnit4::class)
class LocaleFilterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "hi")
    fun libraryLabelsStayEnglishOnAHindiPhone() {
        assertThat(context.getString(Material3R.string.m3c_snackbar_dismiss)).isEqualTo("Dismiss")
    }

    @Test
    @Config(qualifiers = "ar")
    fun libraryLabelsStayEnglishOnAnArabicPhone() {
        assertThat(context.getString(Material3R.string.m3c_snackbar_dismiss)).isEqualTo("Dismiss")
    }
}
