package heycam.buildlogic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModuleGraphRulesTest {
    private fun project(configuration: String, target: String) = DependencyDeclaration(configuration, project = target)
    private fun artifact(configuration: String, coordinates: String) = DependencyDeclaration(configuration, module = coordinates)

    @Test
    fun skeletonDependenciesFollowTheRules() {
        val skeleton = mapOf(
            ":core:model" to listOf(artifact("api", "org.jetbrains.kotlinx:kotlinx-coroutines-core")),
            ":core:common" to listOf(project("api", ":core:model")),
            ":core:data" to listOf(
                project("api", ":core:model"), project("api", ":core:common"),
                artifact("implementation", "androidx.room3:room3-runtime"), artifact("ksp", "androidx.room3:room3-compiler"),
                artifact("implementation", "androidx.sqlite:sqlite-bundled"),
                artifact("androidTestImplementation", "androidx.room3:room3-testing"),
            ),
            ":core:ocr" to listOf(
                project("api", ":core:common"),
                artifact("implementation", "com.google.android.gms:play-services-mlkit-text-recognition"),
            ),
            ":core:scanner" to listOf(artifact("implementation", "com.google.android.gms:play-services-mlkit-document-scanner")),
            ":core:pdf" to listOf(artifact("implementation", "com.tom-roush:pdfbox-android")),
            ":core:security" to listOf(project("api", ":core:common"), project("implementation", ":core:data")),
            ":core:export" to listOf(project("implementation", ":core:data"), project("implementation", ":core:imaging"), project("implementation", ":core:pdf")),
            ":core:domain" to listOf(project("implementation", ":core:ocr"), project("implementation", ":core:scanner")),
            ":core:testing" to listOf(project("api", ":core:domain"), project("api", ":core:export")),
            ":feature:scan" to listOf(project("implementation", ":core:scanner"), project("implementation", ":core:imaging"), project("testImplementation", ":core:testing")),
            ":feature:document" to listOf(project("implementation", ":core:imaging"), project("implementation", ":core:designsystem")),
            ":feature:library" to listOf(project("implementation", ":core:domain"), project("implementation", ":core:data")),
            ":app" to listOf(project("implementation", ":feature:library"), project("implementation", ":core:ocr"), project("testImplementation", ":core:testing")),
        )
        skeleton.forEach { (module, declarations) ->
            assertThat(ModuleGraphRules.violations(module, declarations)).isEmpty()
        }
    }

    @Test
    fun featureToFeatureIsRejected() {
        val problems = ModuleGraphRules.violations(":feature:library", listOf(project("implementation", ":feature:export")))
        assertThat(problems).containsExactly(
            "implementation(project(\":feature:export\")): features must not depend on other features; :app connects them",
        )
    }

    @Test
    fun leafModulesStayLeaves() {
        assertThat(ModuleGraphRules.violations(":core:model", listOf(project("api", ":core:common")))).hasSize(1)
        assertThat(ModuleGraphRules.violations(":core:designsystem", listOf(project("api", ":core:model")))).hasSize(1)
        assertThat(ModuleGraphRules.violations(":core:pdf", listOf(project("implementation", ":core:data")))).hasSize(1)
        assertThat(ModuleGraphRules.violations(":feature:library", listOf(project("implementation", ":core:scanner")))).hasSize(1)
        assertThat(ModuleGraphRules.violations(":feature:export", listOf(project("implementation", ":core:imaging")))).hasSize(1)
    }

    @Test
    fun testingModuleOnlyInTestConfigurations() {
        assertThat(ModuleGraphRules.violations(":core:data", listOf(project("testImplementation", ":core:testing")))).isEmpty()
        assertThat(ModuleGraphRules.violations(":core:data", listOf(project("androidTestImplementation", ":core:testing")))).isEmpty()
        assertThat(ModuleGraphRules.violations(":app", listOf(project("implementation", ":core:testing"))))
            .containsExactly("implementation(project(\":core:testing\")): :core:testing is only for test configurations")
    }

    @Test
    fun restrictedArtifactsStayInTheirModules() {
        assertThat(ModuleGraphRules.violations(":feature:scan", listOf(artifact("implementation", "com.google.mlkit:text-recognition")))).hasSize(1)
        assertThat(ModuleGraphRules.violations(":feature:export", listOf(artifact("testImplementation", "com.tom-roush:pdfbox-android")))).hasSize(1)
        assertThat(ModuleGraphRules.violations(":core:export", listOf(artifact("implementation", "com.tom-roush:pdfbox-android")))).hasSize(1)
        assertThat(ModuleGraphRules.violations(":app", listOf(artifact("implementation", "androidx.room:room-runtime")))).hasSize(1)
        // Non-feature test code may use them, e.g. an end-to-end test reading back an exported PDF.
        assertThat(ModuleGraphRules.violations(":app", listOf(artifact("androidTestImplementation", "com.tom-roush:pdfbox-android")))).isEmpty()
    }

    @Test
    fun nothingDependsOnTheApp() {
        assertThat(ModuleGraphRules.violations(":feature:tools", listOf(project("implementation", ":app"))))
            .containsExactly("implementation(project(\":app\")): nothing depends on :app")
    }

    @Test
    fun unknownModuleNeedsARule() {
        assertThat(ModuleGraphRules.violations(":core:camera", emptyList()))
            .containsExactly(":core:camera has no dependency rule; add one to ModuleGraphRules (PLAN §3.2)")
    }

    @Test
    fun declarationsSurviveEncoding() {
        val declarations = listOf(project("kspDebug", ":core:data"), artifact("api", "group:name"))
        assertThat(declarations.map { DependencyDeclaration.decode(it.encode()) }).isEqualTo(declarations)
    }
}
