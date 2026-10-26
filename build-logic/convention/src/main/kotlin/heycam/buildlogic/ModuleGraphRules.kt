package heycam.buildlogic

/** One dependency as declared in a module's build file: on another module, or on a "group:name" artifact. */
data class DependencyDeclaration(val configuration: String, val project: String? = null, val module: String? = null) {
    init {
        require((project == null) != (module == null)) { "exactly one of project or module" }
    }

    /** Test-only buckets: testImplementation, androidTestImplementation, kspTest, testDebugImplementation, ... */
    val isTestScope: Boolean get() = configuration.contains("test", ignoreCase = true)

    fun encode(): String = if (project != null) "$configuration|project|$project" else "$configuration|module|$module"

    companion object {
        fun decode(encoded: String): DependencyDeclaration {
            val (configuration, kind, value) = encoded.split('|', limit = 3)
            return if (kind == "project") DependencyDeclaration(configuration, project = value)
            else DependencyDeclaration(configuration, module = value)
        }
    }
}

/**
 * The module dependency rules of PLAN §3.2, checked by every module's `checkModuleGraph` task.
 * A new module fails the check until it gets a rule here.
 */
object ModuleGraphRules {
    private const val MODEL = ":core:model"
    private const val COMMON = ":core:common"
    private const val DESIGN = ":core:designsystem"
    private const val DATA = ":core:data"
    private const val IMAGING = ":core:imaging"
    private const val OCR = ":core:ocr"
    private const val SCANNER = ":core:scanner"
    private const val PDF = ":core:pdf"
    private const val EXPORT = ":core:export"
    private const val DOMAIN = ":core:domain"
    private const val SECURITY = ":core:security"
    private const val TESTING = ":core:testing"
    private const val APP = ":app"
    private const val FEATURE_PREFIX = ":feature:"

    private val modelAndCommon = setOf(MODEL, COMMON)
    private val coreForFakes = setOf(MODEL, COMMON, DATA, IMAGING, OCR, SCANNER, PDF, EXPORT, DOMAIN, SECURITY)
    private val featureBase = modelAndCommon + setOf(DESIGN, DATA, DOMAIN, EXPORT, SECURITY)

    private class Restricted(val what: String, val allowedIn: Set<String>, val matches: (group: String, name: String) -> Boolean)

    private val restrictedArtifacts = listOf(
        Restricted("ML Kit", setOf(OCR, SCANNER)) { group, name ->
            group == "com.google.mlkit" || (group == "com.google.android.gms" && name.startsWith("play-services-mlkit"))
        },
        Restricted("PDFBox", setOf(PDF)) { group, _ -> group == "com.tom-roush" },
        Restricted("Room", setOf(DATA)) { group, _ -> group == "androidx.room" || group == "androidx.room3" },
        Restricted("SQLite", setOf(DATA)) { group, _ -> group == "androidx.sqlite" },
    )

    /** Modules [module] may use outside test scopes, or null when the module has no rule yet. */
    fun allowedProjects(module: String): Set<String>? = when {
        module == MODEL || module == DESIGN -> emptySet()
        module == COMMON -> setOf(MODEL)
        module in setOf(DATA, IMAGING, OCR, SCANNER, PDF) -> modelAndCommon
        module == SECURITY -> modelAndCommon + DATA
        module == EXPORT -> modelAndCommon + setOf(DATA, IMAGING, PDF)
        module == DOMAIN -> modelAndCommon + setOf(DATA, IMAGING, OCR, SCANNER)
        module == TESTING -> coreForFakes
        module.startsWith(FEATURE_PREFIX) -> featureBase + when (module) {
            ":feature:scan" -> setOf(SCANNER, IMAGING)
            ":feature:document" -> setOf(IMAGING)
            else -> emptySet()
        }
        else -> null
    }

    /** Human-readable rule breaks; empty when [module]'s declarations follow the rules. */
    fun violations(module: String, declarations: List<DependencyDeclaration>): List<String> {
        if (module != APP && allowedProjects(module) == null) {
            return listOf("$module has no dependency rule; add one to ModuleGraphRules (PLAN §3.2)")
        }
        return declarations.mapNotNull { declaration ->
            declaration.project?.let { projectViolation(module, declaration, it) }
                ?: declaration.module?.let { artifactViolation(module, declaration, it) }
        }.distinct()
    }

    private fun projectViolation(module: String, declaration: DependencyDeclaration, target: String): String? {
        if (target == module) return null
        val where = "${declaration.configuration}(project(\"$target\"))"
        if (target == TESTING) {
            return if (declaration.isTestScope) null else "$where: $TESTING is only for test configurations"
        }
        val allowed = module == APP || target in allowedProjects(module).orEmpty()
        return when {
            allowed -> null
            module.startsWith(FEATURE_PREFIX) && target.startsWith(FEATURE_PREFIX) ->
                "$where: features must not depend on other features; :app connects them"
            target == APP -> "$where: nothing depends on $APP"
            else -> "$where: $module may not depend on $target"
        }
    }

    private fun artifactViolation(module: String, declaration: DependencyDeclaration, coordinates: String): String? {
        if (declaration.isTestScope && !module.startsWith(FEATURE_PREFIX)) return null
        val group = coordinates.substringBefore(':')
        val name = coordinates.substringAfter(':', missingDelimiterValue = "")
        val rule = restrictedArtifacts.firstOrNull { it.matches(group, name) } ?: return null
        return if (module in rule.allowedIn) null
        else "${declaration.configuration}(\"$coordinates\"): ${rule.what} belongs only in ${rule.allowedIn.sorted().joinToString()}"
    }
}
