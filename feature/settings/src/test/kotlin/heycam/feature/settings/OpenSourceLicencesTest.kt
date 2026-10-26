package heycam.feature.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenSourceLicencesTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun asset(path: String) = app.assets.open(path).bufferedReader().use { it.readText() }

    @Test
    fun everyListedTextShipsWithTheApp() {
        val paths = OpenSourceLicences.components.map { it.text }.filterIsInstance<LicenceText.Asset>()
            .flatMap { listOfNotNull(it.path, it.followedBy) }
            .distinct()
        paths.forEach { path -> assertWithMessage(path).that(asset(path).length).isGreaterThan(1_000) }
    }

    @Test
    fun theApacheTextIsTheLicence() {
        val text = asset(OpenSourceLicences.APACHE_2_ASSET)
        assertThat(text).contains("Apache License")
        assertThat(text).contains("Version 2.0, January 2004")
        assertThat(OpenSourceLicences.components.first().name).isEqualTo("heycam")
    }

    /** Apache-2.0 4(d): PDFBox's NOTICE goes wherever third-party notices are shown, with its bundled data's terms. */
    @Test
    fun pdfBoxCarriesItsNoticeAndTheDataItBundles() {
        val pdfbox = OpenSourceLicences.components.single { it.name == "PDFBox-Android" }.text
        assertThat(pdfbox).isEqualTo(LicenceText.Asset(OpenSourceLicences.PDFBOX_NOTICE_ASSET, followedBy = OpenSourceLicences.APACHE_2_ASSET))
        val notice = asset(OpenSourceLicences.PDFBOX_NOTICE_ASSET)
        assertThat(notice).startsWith("Apache PDFBox\nCopyright 2014 The Apache Software Foundation")
        listOf(
            "Copyright (c) 2002-2007, www.pdfbox.org",
            "Adobe Font Metrics (AFM) for PDF Core 14 Fonts",
            "CMaps for PDF Fonts",
            "Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated.",
            "ITC Zapf Dingbats Glyph List",
            "UNICODE LICENSE V3",
        ).forEach { assertThat(notice).contains(it) }
    }

    @Test
    fun bundledLibrariesAndFontsAreListed() {
        val names = OpenSourceLicences.components.map { it.name }
        assertThat(names).containsAtLeast(
            "Coil", "Okio", "Protocol Buffers", "Liberation Sans", "SQLite",
            "Accompanist Drawable Painter", "javax.inject", "Jakarta Dependency Injection",
        )
        assertThat(asset(OpenSourceLicences.PROTOBUF_ASSET)).startsWith("Copyright 2008 Google Inc.")
        assertThat(asset(OpenSourceLicences.LIBERATION_SANS_ASSET)).contains("SIL OPEN FONT LICENSE Version 1.1")
    }
}
