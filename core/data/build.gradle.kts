plugins { id("heycam.android.library"); id("heycam.hilt"); alias(libs.plugins.room3); alias(libs.plugins.kotlin.serialization) }
android { namespace = "heycam.core.data" }
room3 { schemaDirectory("$projectDir/schemas") }
dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(libs.room3.runtime)
    ksp(libs.room3.compiler)
    implementation(libs.sqlite.bundled)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.room3.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}

// JVM unit tests run Room on the same bundled SQLite (FTS5 + trigram) the app ships. The Android driver loads its
// native library from the path in two documented system properties; the host build of that library comes from the
// driver's JVM artifact (same version), so tests never fall back to a platform SQLite without FTS5.
val hostSqlite: Configuration = configurations.create("hostSqlite") {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies { hostSqlite("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqlite.get()}") }

val hostSqliteNative: Pair<String, String> = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> (if (arm) "osx_arm64" else "osx_x64") to "libsqliteJni.dylib"
        os.contains("win") -> "windows_x64" to "sqliteJni.dll"
        else -> (if (arm) "linux_arm64" else "linux_x64") to "libsqliteJni.so"
    }
}
interface ArchiveAccess { @get:Inject val archives: ArchiveOperations }
val extractHostSqlite = tasks.register<Sync>("extractHostSqlite") {
    val archives = objects.newInstance<ArchiveAccess>().archives
    from(hostSqlite.elements.map { files -> archives.zipTree(files.single()) }) { include("natives/**") }
    into(layout.buildDirectory.dir("host-sqlite"))
}
tasks.withType<Test>().configureEach {
    dependsOn(extractHostSqlite)
    val nativeDir = layout.buildDirectory.dir("host-sqlite/natives/${hostSqliteNative.first}").get().asFile
    systemProperty("androidx.sqlite.driver.bundled.path", nativeDir.absolutePath)
    systemProperty("androidx.sqlite.driver.bundled.name", hostSqliteNative.second)
}
