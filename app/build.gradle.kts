import heycam.buildlogic.AppVersion
import heycam.buildlogic.UploadKey

plugins { id("heycam.android.application") }

// Release signing on a developer machine only (HEYCAM_KEYSTORE_*; see README). CI builds unsigned and signs the
// bundle in release.yml's approval-gated publish job, so Gradle never sees the key there.
val uploadKey = UploadKey.fromEnvironment { providers.environmentVariable(it).orNull }

// -Pheycam.testBuildType=release runs the device tests against the R8-minified build (PLAN D23).
val testOnMinifiedBuild = providers.gradleProperty("heycam.testBuildType").orNull == "release"

android {
    namespace = "heycam.app"
    defaultConfig {
        applicationId = "in.edgr.heycam" // permanent after the first Play upload
        // CI passes both from the release tag (scripts/version-from-tag.sh); local builds get 1 / 0.0.0-dev.
        versionCode = AppVersion.code(providers.gradleProperty("heycam.versionCode").orNull)
        versionName = AppVersion.name(providers.gradleProperty("heycam.versionName").orNull)
        // Device tests run in Hilt's test application (app/src/androidTest, PLAN §6 level 4).
        testInstrumentationRunner = "heycam.app.HeycamTestRunner"
    }
    // heycam's strings are English only; library translations would mix languages and can flip the layout to
    // right-to-left. Add a language here when heycam's own strings are translated into it.
    androidResources { localeFilters += listOf("en") }
    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey.storeFile)
                storePassword = uploadKey.storePassword
                keyAlias = uploadKey.alias
                keyPassword = uploadKey.keyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
                ?: signingConfigs.getByName("debug").takeIf { testOnMinifiedBuild }
            if (testOnMinifiedBuild) {
                // The app keeps only what the test APK calls into; everything else ships as it would to Play.
                proguardFile("r8-test-entrypoints.pro")
                testProguardFile("r8-instrumentation-keep.pro")
            }
        }
    }
    if (testOnMinifiedBuild) testBuildType = "release"
}

if (testOnMinifiedBuild) {
    // Before a device run on the minified app: R8 may have removed something the test APK calls, which would only
    // show on the device as NoSuchMethodError. scripts/check-test-apk.py finds it from the two APKs and prints the rule.
    val androidJars = androidComponents.sdkComponents.bootClasspath
    val checkTestApk = tasks.register<Exec>("checkReleaseTestApk") {
        group = "verification"
        description = "Checks that the R8 release app keeps everything its device tests call."
        dependsOn("assembleRelease", "assembleReleaseAndroidTest")
        workingDir = rootDir
        executable = "python3"
        args("-I", "scripts/check-test-apk.py")
        argumentProviders.add { androidJars.get().flatMap { listOf("--platform", it.asFile.path) } }
    }
    tasks.matching { it.name == "connectedReleaseAndroidTest" }.configureEach { dependsOn(checkTestApk) }
}

dependencies {
    listOf(
        ":core:model", ":core:common", ":core:designsystem", ":core:data", ":core:imaging", ":core:ocr",
        ":core:scanner", ":core:pdf", ":core:export", ":core:domain", ":core:security",
        ":feature:library", ":feature:scan", ":feature:document", ":feature:export", ":feature:tools", ":feature:settings",
    ).forEach { implementation(project(it)) }
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest) // hosts createComposeRule in debug and release unit tests

    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
