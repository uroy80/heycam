pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "heycam"
// Module layout and dependency rules: PLAN §3.1 and §3.2 (enforced by ./gradlew checkModuleGraph).
include(
    ":app",
    ":core:model", ":core:common", ":core:designsystem", ":core:data", ":core:imaging", ":core:ocr", ":core:scanner",
    ":core:pdf", ":core:export", ":core:domain", ":core:security",
    ":feature:library", ":feature:scan", ":feature:document", ":feature:export",
    ":feature:tools", ":feature:settings",
    ":core:testing", // fakes, sample pages and test rules; test configurations only
)
