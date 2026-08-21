import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // AGP 8.x has no built-in Kotlin support — the Kotlin Android plugin must be explicit.
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.field360.traker.snap"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Published AAR ships R8-obfuscated — see fieldtrack-core/build.gradle.kts.
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

    // Publishes the release variant with a javadoc jar — no sources jar, for the same
    // IP-protection reason as fieldtrack-core: an obfuscated AAR next to its own source
    // is not obfuscated (BUILD.md §5.6).
    //
    // Declared per module rather than in gradle/publish.gradle.kts because AGP's types do
    // not resolve inside a script plugin — see that file. Without this block AGP creates
    // no `release` software component and there is nothing to publish.
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
    // The engine's port only. This artifact knows nothing about capture, storage or
    // Android location — it turns a list of coordinates into a list of coordinates.
    implementation(project(":fieldtrack-geo"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // compileOnly, exactly as fieldtrack-sync treats it: the shipped provider happens to use
    // OkHttp, but a host writing its own RoadSnapProvider should not inherit an HTTP
    // client it never asked for.
    compileOnly(libs.okhttp)
    compileOnly(libs.retrofit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.retrofit)
    testImplementation(libs.okhttp.mockwebserver)
}

// Publishing — coordinates, POM, sources and javadoc jars. See the script for why it
// is shared rather than repeated in six build files (CROSS-PLATFORM.md R-44).
apply(from = rootProject.file("gradle/publish.gradle.kts"))
