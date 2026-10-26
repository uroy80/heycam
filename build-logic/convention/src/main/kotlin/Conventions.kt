import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import heycam.buildlogic.CheckImportsTask
import heycam.buildlogic.CheckModuleGraphTask
import heycam.buildlogic.CheckPermissionsTask
import heycam.buildlogic.DependencyDeclaration
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

internal const val MIN_SDK = 28
internal const val TARGET_SDK = 37
internal const val COMPILE_SDK = 37

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.lib(alias: String) = libs.findLibrary(alias).get()

/**
 * Unit tests: Robolectric (SDK 36/37 sandbox) on JDK 21 needs these exports (toolchain spike).
 *
 * Gradle 9 fails a test task that discovers no tests while it sees test classes. AGP and Hilt generate classes into
 * every unit-test variant, so a module with no tests of its own failed `testDebugUnitTest`. The check stays on for
 * modules that have test sources (src/test, src/testDebug, ...), where finding nothing means a broken setup.
 */
internal fun Project.configureUnitTests() {
    val hasOwnTests = file("src").listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("test") }
        .any { dir -> dir.walkTopDown().any { it.isFile && (it.extension == "kt" || it.extension == "java") } }
    tasks.withType<Test>().configureEach {
        jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        failOnNoDiscoveredTests.set(hasOwnTests)
    }
}

/**
 * In a module with device tests, every connected<Debug|Release>AndroidTest ends with scripts/check-test-counts.py
 * for that module (check<Debug|Release>DeviceTestCount). Gradle reports success when a test APK crashes at startup
 * and runs nothing; the check fails the build when the module ran no tests, fewer than its minimum, or any failed.
 */
internal fun Project.registerDeviceTestCountChecks() {
    val sources = file("src/androidTest")
    val hasDeviceTests = sources.isDirectory &&
        sources.walkTopDown().any { it.isFile && (it.extension == "kt" || it.extension == "java") }
    if (!hasDeviceTests) return
    val module = path.removePrefix(":").replace(':', '/')
    val root = isolated.rootProject.projectDirectory.asFile
    for (buildType in listOf("debug", "release")) {
        val name = buildType.replaceFirstChar(Char::uppercase)
        val check = tasks.register<Exec>("check${name}DeviceTestCount") {
            group = "verification"
            description = "Checks that this module's $buildType device tests ran and passed (scripts/check-test-counts.py)."
            workingDir = root
            executable = "python3"
            args("-I", "scripts/check-test-counts.py", buildType, "--module", module)
        }
        tasks.matching { it.name == "connected${name}AndroidTest" }.configureEach { finalizedBy(check) }
    }
}

internal fun Lint.heycamDefaults() {
    warningsAsErrors = false
    abortOnError = true
    checkDependencies = false
}

/** `checkModuleGraph` and `checkImports` in every module; both also run with `check`. */
internal fun Project.registerArchitectureChecks() {
    val module = path // inside the task blocks below, `path` would be the task's own path
    val moduleGraph = tasks.register<CheckModuleGraphTask>("checkModuleGraph") {
        group = "verification"
        description = "Checks this module's dependencies against the module rules (PLAN §3.2)."
        modulePath.set(module)
        declarations.set(provider { declaredDependencies() })
        report.set(layout.buildDirectory.file("reports/heycam/module-graph.txt"))
    }
    val imports = tasks.register<CheckImportsTask>("checkImports") {
        group = "verification"
        description = "Checks this module's shipped sources for forbidden imports (PLAN §3.2, §3.8)."
        modulePath.set(module)
        sources.from(fileTree("src") { include("**/*.kt", "**/*.java"); exclude("test*/**", "androidTest*/**") })
        moduleDirectory.set(layout.projectDirectory)
        report.set(layout.buildDirectory.file("reports/heycam/imports.txt"))
    }
    tasks.named("check") { dependsOn(moduleGraph, imports) }
}

private fun Project.declaredDependencies(): List<String> = configurations.flatMap { configuration ->
    configuration.dependencies.mapNotNull { dependency ->
        when (dependency) {
            is ProjectDependency -> DependencyDeclaration(configuration.name, project = dependency.path)
            is ExternalModuleDependency -> DependencyDeclaration(configuration.name, module = "${dependency.group}:${dependency.name}")
            else -> null
        }?.encode()
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library") // AGP 9: Kotlin is built in, no kotlin-android plugin
        extensions.configure<LibraryExtension> {
            compileSdk { version = release(COMPILE_SDK) }
            defaultConfig {
                minSdk = MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions.unitTests.isIncludeAndroidResources = true
            lint { heycamDefaults() }
        }
        configureUnitTests()
        registerArchitectureChecks()
        registerDeviceTestCountChecks()
        dependencies {
            add("implementation", lib("kotlinx-coroutines-android"))
            add("testImplementation", lib("junit"))
            add("testImplementation", lib("truth"))
            add("testImplementation", lib("kotlinx-coroutines-test"))
            add("testImplementation", lib("turbine"))
            add("testImplementation", lib("robolectric"))
            add("testImplementation", lib("androidx-test-core"))
            add("androidTestImplementation", lib("androidx-test-runner"))
            add("androidTestImplementation", lib("androidx-test-ext-junit"))
            add("androidTestImplementation", lib("truth"))
        }
    }
}

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        dependencies {
            add("implementation", lib("hilt-android"))
            add("ksp", lib("hilt-compiler"))
        }
    }
}

/** Feature module = Android library + Compose + Hilt + serialization (type-safe routes). */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("heycam.android.library")
        pluginManager.apply("heycam.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        extensions.configure<LibraryExtension> { buildFeatures.compose = true }
        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:common"))
            add("implementation", project(":core:designsystem"))
            add("implementation", platform(lib("compose-bom")))
            add("implementation", lib("compose-ui"))
            add("implementation", lib("compose-material3"))
            add("implementation", lib("compose-ui-tooling-preview"))
            add("debugImplementation", lib("compose-ui-tooling"))
            add("implementation", lib("androidx-navigation-compose"))
            add("implementation", lib("androidx-lifecycle-runtime-compose"))
            add("implementation", lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", lib("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", lib("kotlinx-serialization-json"))
            add("testImplementation", platform(lib("compose-bom")))
            add("testImplementation", lib("compose-ui-test-junit4"))
            add("debugImplementation", lib("compose-ui-test-manifest"))
        }
    }
}

/**
 * The app module. Adds `checkPermissions`: the merged release manifest against config/permissions-allowlist.txt
 * (PLAN §3.5), also run by `check`.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("heycam.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        extensions.configure<ApplicationExtension> {
            compileSdk { version = release(COMPILE_SDK) }
            defaultConfig {
                minSdk = MIN_SDK
                targetSdk = TARGET_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            buildFeatures.compose = true
            testOptions.unitTests.isIncludeAndroidResources = true
            lint { heycamDefaults() }
        }
        configureUnitTests()
        registerArchitectureChecks()
        registerDeviceTestCountChecks()
        registerPermissionCheck()
    }

    private fun Project.registerPermissionCheck() {
        val allowlist = isolated.rootProject.projectDirectory.file("config/permissions-allowlist.txt")
        val checkPermissions = tasks.register("checkPermissions") {
            group = "verification"
            description = "Checks the release manifest's permissions against config/permissions-allowlist.txt."
        }
        tasks.named("check") { dependsOn(checkPermissions) }
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants(selector().withBuildType("release")) { variant ->
                val name = variant.name.replaceFirstChar(Char::uppercase)
                val check = tasks.register<CheckPermissionsTask>("check${name}Permissions") {
                    mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    this.allowlist.set(allowlist)
                    applicationId.set(variant.applicationId)
                    report.set(layout.buildDirectory.file("reports/heycam/permissions-${variant.name}.txt"))
                }
                checkPermissions.configure { dependsOn(check) }
            }
        }
    }
}
