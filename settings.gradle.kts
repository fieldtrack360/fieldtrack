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

rootProject.name = "trackit"

include(":trackit-geo")
include(":trackit-core")
include(":trackit-maps")
include(":trackit-sync")
include(":trackit-snap")
include(":fieldtrack")

// Exclude sample app from JitPack builds to avoid dependency resolution issues
// and speed up artifact publishing.
if (System.getenv("JITPACK") != "true") {
    include(":sample-android")
}
