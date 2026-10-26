plugins {
    id("heycam.android.feature")
    alias(libs.plugins.roborazzi)
}
android {
    namespace = "heycam.feature.document"
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
    implementation(project(":core:imaging"))
    implementation(project(":core:export"))
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
