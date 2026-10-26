plugins { `kotlin-dsl` }

dependencies {
    compileOnly(libs.android.gradle.plugin)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test {
    val allowlist = layout.projectDirectory.file("../../config/permissions-allowlist.txt")
    inputs.file(allowlist).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("heycam.permissionsAllowlist", allowlist.asFile.path)
}

gradlePlugin {
    plugins {
        register("androidLibrary") { id = "heycam.android.library"; implementationClass = "AndroidLibraryConventionPlugin" }
        register("androidFeature") { id = "heycam.android.feature"; implementationClass = "AndroidFeatureConventionPlugin" }
        register("androidApplication") { id = "heycam.android.application"; implementationClass = "AndroidApplicationConventionPlugin" }
        register("hilt") { id = "heycam.hilt"; implementationClass = "HiltConventionPlugin" }
    }
}
