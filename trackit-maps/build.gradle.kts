import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.devstree.trackit.maps"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Published AAR ships R8-obfuscated — see trackit-core/build.gradle.kts.
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
    // IP-protection reason as trackit-core: an obfuscated AAR next to its own source
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
    // Only the pure engine and the Maps SDK. No Hilt: a renderer is constructed where
    // the map is, and forcing DI on a drawing helper buys nothing.
    api(project(":trackit-geo"))
    implementation(libs.play.services.maps)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Publishing — coordinates, POM, sources and javadoc jars. See the script for why it
// is shared rather than repeated in six build files (CROSS-PLATFORM.md R-44).
apply(from = rootProject.file("gradle/publish.gradle.kts"))
