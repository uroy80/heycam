plugins { id("heycam.android.library"); id("heycam.hilt") }
android { namespace = "heycam.core.pdf" }
dependencies {
    api(project(":core:model")); api(project(":core:common"))
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
}
