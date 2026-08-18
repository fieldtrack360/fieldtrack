import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.devstree.traker"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "SDK_LOGGING_ENABLED", "true")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("boolean", "SDK_LOGGING_ENABLED", "false")
            // The published AAR ships R8-obfuscated: internals renamed and flattened
            // into com.devstree.traker.internal, so decompiling the artifact yields
            // the public contract and little else — whether or not the host minifies.
            // proguard-rules.pro is the build-time config; consumer-rules.pro remains
            // the host-side one, and the two are deliberately separate files.
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
    // NOTE: schemas/ is deliberately NOT wired into androidTest assets. AGP 9 throws
    // `DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet`
    // on sourceSets.getByName(...).assets. Room's MigrationTestHelper will need that
    // path once version 2 exists — revisit with the Room Gradle plugin then.

    // Publishes the release variant with a javadoc jar — and deliberately NO sources
    // jar. The release AAR is obfuscated for IP protection, and a -sources.jar next to
    // it would hand every consumer the exact code the obfuscation hides. The cost is
    // real and accepted: no source navigation in a host's IDE (BUILD.md §5.6).
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

// EC-83 — schemas are committed and migrations are real; never destructive.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":fieldtrack-geo"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // No DI framework, deliberately. The graph is wired by hand in di/TrakerGraph.kt so
    // that a host needs no Gradle plugin, no `@HiltAndroidApp`, and no annotation
    // processor of its own — see Traker's KDoc and CROSS-PLATFORM.md B-1. KSP below is
    // Room's alone.

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
}

// Publishing — coordinates, POM, sources and javadoc jars. See the script for why it
// is shared rather than repeated in six build files (CROSS-PLATFORM.md R-44).
apply(from = rootProject.file("gradle/publish.gradle.kts"))
