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
    }
}

rootProject.name = "traker"

include(":fieldtrack-geo")
include(":fieldtrack-core")
include(":fieldtrack-maps")
include(":fieldtrack-sync")
include(":fieldtrack-snap")
include(":fieldtrack")

// Exclude sample app from JitPack builds to avoid dependency resolution issues
// and speed up artifact publishing.
if (System.getenv("JITPACK") != "true") {
    include(":sample-android")
}
