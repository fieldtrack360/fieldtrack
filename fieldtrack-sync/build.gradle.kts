import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.devstree.traker.sync"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "SDK_LOGGING_ENABLED", "true")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("boolean", "SDK_LOGGING_ENABLED", "false")
            // Published AAR ships R8-obfuscated — see fieldtrack-core/build.gradle.kts.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
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
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Only the public seam from core — never its internals.
    implementation(project(":fieldtrack-core"))
    implementation(libs.androidx.core.ktx)


    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // compileOnly: the default transport uses OkHttp, but a host supplying its own
    // SyncTransport should not inherit it. See SyncTransport's KDoc.
    compileOnly(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}

// Publishing — coordinates, POM, sources and javadoc jars. See the script for why it
// is shared rather than repeated in six build files (CROSS-PLATFORM.md R-44).
apply(from = rootProject.file("gradle/publish.gradle.kts"))
