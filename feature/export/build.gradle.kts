plugins {
    id("heycam.android.feature")
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "heycam.feature.export"
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
    implementation(project(":core:export"))
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
