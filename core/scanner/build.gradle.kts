plugins { id("heycam.android.library"); id("heycam.hilt") }
android { namespace = "heycam.core.scanner" }
dependencies {
    api(project(":core:model")); api(project(":core:common"))
    api(libs.androidx.activity.compose)
    implementation(libs.mlkit.document.scanner)
    implementation(libs.kotlinx.coroutines.play.services)
}
