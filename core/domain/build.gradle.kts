plugins { id("heycam.android.library"); id("heycam.hilt") }
android { namespace = "heycam.core.domain" }
dependencies {
    api(project(":core:model")); api(project(":core:common"))
    implementation(project(":core:data")); implementation(project(":core:imaging"))
    implementation(project(":core:ocr")); implementation(project(":core:scanner"))
}
