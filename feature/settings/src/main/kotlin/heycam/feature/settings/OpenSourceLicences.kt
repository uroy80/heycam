package heycam.feature.settings

/** Where a licence's full text lives: a file in the app's assets, or a page on the web. */
sealed interface LicenceText {
    /** [path] opens first; [followedBy], when set, is printed after it (a NOTICE above its licence). */
    data class Asset(val path: String, val followedBy: String? = null) : LicenceText
    data class Web(val url: String) : LicenceText
}

data class OpenSourceComponent(val name: String, val owner: String, val licence: String, val text: LicenceText)

/**
 * What heycam ships, by component family (PLAN 3.4 catalog, D17 icons, 4.11 fonts), including what comes in with a
 * library: PDFBox-Android's notices, Adobe font data and Liberation Sans, and the protobuf runtime inside DataStore.
 * Names and owners are proper nouns, so they are not translated. The font licences are the files
 * :core:designsystem bundles in assets/licences; a device test checks that every asset listed here is in the APK.
 */
object OpenSourceLicences {
    const val APACHE_2_ASSET = "licences/apache-2.0.txt"
    /** Apache PDFBox's NOTICE and the external-component licences of PdfBox-Android's LICENSE.txt. */
    const val PDFBOX_NOTICE_ASSET = "licences/pdfbox-android-notice.txt"
    const val LIBERATION_SANS_ASSET = "licences/liberation-sans-OFL.txt"
    const val PROTOBUF_ASSET = "licences/protobuf-BSD-3.txt"
    const val ML_KIT_TERMS_URL = "https://developers.google.com/ml-kit/terms"
    const val SQLITE_URL = "https://sqlite.org/copyright.html"

    private const val APACHE_2 = "Apache-2.0"
    private const val OFL = "OFL-1.1"
    private val apache = LicenceText.Asset(APACHE_2_ASSET)

    val components: List<OpenSourceComponent> = listOf(
        OpenSourceComponent("heycam", "Usham Roy", APACHE_2, apache),
        OpenSourceComponent("AndroidX and Jetpack Compose", "The Android Open Source Project", APACHE_2, apache),
        OpenSourceComponent("Accompanist Drawable Painter", "The Android Open Source Project", APACHE_2, apache),
        OpenSourceComponent("Dagger and Hilt", "The Dagger Authors", APACHE_2, apache),
        OpenSourceComponent("javax.inject", "The JSR-330 Expert Group", APACHE_2, apache),
        OpenSourceComponent("Jakarta Dependency Injection", "Eclipse Foundation", APACHE_2, apache),
        OpenSourceComponent("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains s.r.o. and contributors", APACHE_2, apache),
        OpenSourceComponent("Coil", "Coil Contributors", APACHE_2, apache),
        OpenSourceComponent("Okio", "Square, Inc.", APACHE_2, apache),
        OpenSourceComponent(
            "PDFBox-Android",
            "Tom Roush; Apache PDFBox by The Apache Software Foundation",
            APACHE_2,
            LicenceText.Asset(PDFBOX_NOTICE_ASSET, followedBy = APACHE_2_ASSET),
        ),
        OpenSourceComponent("Protocol Buffers", "Google LLC", "BSD-3-Clause", LicenceText.Asset(PROTOBUF_ASSET)),
        OpenSourceComponent("SQLite", "The SQLite authors", "Public domain", LicenceText.Web(SQLITE_URL)),
        OpenSourceComponent("Material Symbols and Material Icons", "Google LLC", APACHE_2, apache),
        OpenSourceComponent("Fraunces", "The Fraunces Project Authors", OFL, LicenceText.Asset("licences/fraunces-OFL.txt")),
        OpenSourceComponent("Libre Franklin", "The Libre Franklin Project Authors", OFL, LicenceText.Asset("licences/libre-franklin-OFL.txt")),
        OpenSourceComponent("IBM Plex Mono", "IBM Corp.", OFL, LicenceText.Asset("licences/ibm-plex-mono-OFL.txt")),
        OpenSourceComponent("Liberation Sans", "Red Hat, Inc. and Google", OFL, LicenceText.Asset(LIBERATION_SANS_ASSET)),
        OpenSourceComponent("Google ML Kit", "Google LLC", "ML Kit terms", LicenceText.Web(ML_KIT_TERMS_URL)),
    )
}
