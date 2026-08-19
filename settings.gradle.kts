pluginManagement {
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
        mavenLocal()
        mavenCentral()
        // JitPack serves the published SDK artifacts (com.github.fieldtrack360.fieldtrack).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "traker"

include(":fieldtrack-geo")
include(":fieldtrack-core")
include(":fieldtrack-maps")
include(":fieldtrack-sync")
include(":fieldtrack-snap")
include(":fieldtrack")

// Custom lint rules. Not published on its own — `lintPublish` bundles the jar into the
// AARs, so the checks run in the host app's build (docs/DEVICE-INTEGRITY-PLAN.md §7).
include(":fieldtrack-lint")

// Exclude sample app from JitPack builds to avoid dependency resolution issues
// and speed up artifact publishing.
if (System.getenv("JITPACK") != "true") {
    include(":sample-android")
}
