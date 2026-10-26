// Hand-written fakes for the §4 interfaces, sample pages and test rules (PLAN §4.13, D26). Each interface's owner
// writes its fakes in its own subpackage. Only ever a testImplementation / androidTestImplementation dependency
// (checkModuleGraph enforces it).
plugins { id("heycam.android.library"); id("heycam.hilt") }
android { namespace = "heycam.core.testing" }
dependencies {
    listOf(
        ":core:model", ":core:common", ":core:data", ":core:imaging", ":core:ocr",
        ":core:scanner", ":core:pdf", ":core:export", ":core:domain", ":core:security",
    ).forEach { api(project(it)) }
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.androidx.test.core)
    // @TestInstallIn modules that swap the real bindings for these fakes.
    api(libs.hilt.android.testing)
}
