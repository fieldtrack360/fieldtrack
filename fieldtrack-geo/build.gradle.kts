import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // AGP 8.x has no built-in Kotlin support — the Kotlin Android plugin must be explicit.
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * PLAN.md §3 invariant 2: no platform types inside `fieldtrack-geo`. The source remains
 * pure Kotlin; Android library packaging is used so R8 can protect the published code.
 */
android {
    namespace = "com.field360.traker.geo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaBytecode.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaBytecode.get())
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }

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
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaBytecode.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
}

// Publishing — coordinates, POM, sources and javadoc jars. See the script for why it
// is shared rather than repeated in six build files (CROSS-PLATFORM.md R-44).
apply(from = rootProject.file("gradle/publish.gradle.kts"))
