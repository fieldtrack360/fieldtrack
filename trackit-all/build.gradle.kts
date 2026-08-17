import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// Umbrella module: no code of its own. It exists so a host app can depend on
// `com.devstree.trackit:trackit-all` and get the whole SDK transitively instead of
// listing every module. Every dependency below is `api` on purpose — the entire point
// is to re-export them to the consumer's compile classpath.
android {
    namespace = "com.devstree.trackit.all"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }

    // No R8 here: the AAR is empty, and the modules it aggregates are already
    // obfuscated individually (BUILD.md §5.6).
    publishing {
        singleVariant("release") {
            withJavadocJar()
        }
    }
}

kotlin {
    // Every public declaration needs an explicit visibility and return type - accidental
    // API surface is how an SDK grows things it can never remove.
    explicitApi()

    jvmToolchain(libs.versions.javaTarget.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // :trackit-geo arrives transitively — trackit-core exposes it with `api`.
    api(project(":trackit-core"))
    api(project(":trackit-maps"))
    api(project(":trackit-sync"))
    api(project(":trackit-snap"))
}

// Publishing — coordinates, POM, sources and javadoc jars. See the script for why it
// is shared rather than repeated in six build files (CROSS-PLATFORM.md R-44).
apply(from = rootProject.file("gradle/publish.gradle.kts"))
