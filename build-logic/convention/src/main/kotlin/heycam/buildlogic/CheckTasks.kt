package heycam.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/** Fails when the module's declared dependencies break [ModuleGraphRules]. */
@CacheableTask
abstract class CheckModuleGraphTask : DefaultTask() {
    @get:Input abstract val modulePath: Property<String>

    /** [DependencyDeclaration.encode]d, so the configuration cache stores plain strings. */
    @get:Input abstract val declarations: ListProperty<String>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val module = modulePath.get()
        val problems = ModuleGraphRules.violations(module, declarations.get().map(DependencyDeclaration::decode))
        report.get().asFile.writeText(problems.joinToString("\n", postfix = "\n").ifBlank { "OK\n" })
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n", prefix = "Module graph rules broken in $module (PLAN §3.2):\n") { "  - $it" })
        }
    }
}

/** Fails when the module's shipped sources break [ImportRules]. */
@CacheableTask
abstract class CheckImportsTask : DefaultTask() {
    @get:Input abstract val modulePath: Property<String>

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Only used to print short paths. */
    @get:Internal abstract val moduleDirectory: DirectoryProperty

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val module = modulePath.get()
        val root = moduleDirectory.get().asFile
        val problems = sources.files.sorted().flatMap { file ->
            ImportRules.violations(module, file.readLines()).map { "${file.relativeTo(root)}:${it.line}: ${it.message}" }
        }
        report.get().asFile.writeText(problems.joinToString("\n", postfix = "\n").ifBlank { "OK\n" })
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n", prefix = "Source rules broken in $module:\n") { "  - $it" })
        }
    }
}

/** Fails when the merged manifest requests a permission that config/permissions-allowlist.txt does not list. */
@CacheableTask
abstract class CheckPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowlist: RegularFileProperty

    @get:Input abstract val applicationId: Property<String>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val requested = PermissionRules.requestedPermissions(mergedManifest.get().asFile)
        val allowed = PermissionRules.parseAllowlist(allowlist.get().asFile.readText(), applicationId.get())
        val unexpected = PermissionRules.unexpected(requested, allowed)
        report.get().asFile.writeText(requested.sorted().joinToString("\n", postfix = "\n"))
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                unexpected.joinToString(
                    "\n",
                    prefix = "The release manifest requests permissions that are not in config/permissions-allowlist.txt.\n" +
                        "Remove the dependency that adds them, or review Data safety and then extend the allowlist:\n",
                ) { "  - $it" },
            )
        }
    }
}
