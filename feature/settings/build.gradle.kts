plugins {
    id("heycam.android.feature")
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "heycam.feature.settings"
    // Screenshot tests run on Robolectric native graphics; "hardware" pixel copy renders layers like a device.
    testOptions.unitTests.all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware") }
}

roborazzi {
    // Goldens live in the repo; `recordRoborazziDebug` writes them, `verifyRoborazziDebug` (CI) compares.
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
