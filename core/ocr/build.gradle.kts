plugins { id("heycam.android.library"); id("heycam.hilt") }
android {
    namespace = "heycam.core.ocr"
    buildFeatures.buildConfig = true
    defaultConfig {
        // PageText.engine records which model read a page: "mlkit-text-v2/<artifact version>" (PLAN 3.9).
        buildConfigField("String", "MLKIT_TEXT_LATIN", "\"${libs.versions.mlkitTextUnbundledLatin.get()}\"")
        buildConfigField("String", "MLKIT_TEXT_SCRIPTS", "\"${libs.versions.mlkitTextUnbundledScripts.get()}\"")
    }
}
dependencies {
    api(project(":core:model")); api(project(":core:common"))
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.kotlinx.coroutines.play.services)
}
dependencies {
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
