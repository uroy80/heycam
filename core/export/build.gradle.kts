plugins { id("heycam.android.library"); id("heycam.hilt") }
android { namespace = "heycam.core.export" }
dependencies {
    api(project(":core:model")); api(project(":core:common"))
    implementation(project(":core:data")); implementation(project(":core:imaging")); implementation(project(":core:pdf"))
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.ktx)
    // Tests read the exported PDFs back.
    testImplementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
}
