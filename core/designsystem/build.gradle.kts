// Broadsheet design system: theme, fonts, icons, components (PLAN 4.11). Compose only; no model, no DI.
plugins {
    id("heycam.android.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "heycam.core.designsystem"
    buildFeatures.compose = true
    // Screenshot tests run on Robolectric native graphics; "hardware" pixel copy renders layers like a device.
    testOptions.unitTests.all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware") }
}

roborazzi {
    // Goldens live in the repo; `recordRoborazziDebug` writes them, `verifyRoborazziDebug` (CI) compares.
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

/** Hands the Play listing folder (store/, owned with this module) to StoreListingTest as a tracked input. */
class StoreListingDir(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dheycam.storeDir=${dir.absolutePath}")
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(StoreListingDir(rootDir.resolve("store")))
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
}
