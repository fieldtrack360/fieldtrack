import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
group = "com.github.fieldtrack360.fieldtrack"
version = catalog.findVersion("trackit").get().requiredVersion

subprojects {
    group = "com.github.fieldtrack360.fieldtrack"
}

tasks.register<VerifyReleaseObfuscationTask>("verifyReleaseObfuscation") {
    group = "verification"
    description = "Builds and audits every TrackIt release artifact for obfuscation leaks."
    repositoryRoot.set(layout.projectDirectory)

    dependsOn(
        ":trackit-geo:assembleRelease",
        ":trackit-core:assembleRelease",
        ":trackit-maps:assembleRelease",
        ":trackit-snap:assembleRelease",
        ":trackit-sync:assembleRelease",
        ":trackit-all:assembleRelease",
        ":trackit-geo:javaDocReleaseJar",
        ":trackit-core:javaDocReleaseJar",
        ":trackit-maps:javaDocReleaseJar",
        ":trackit-snap:javaDocReleaseJar",
        ":trackit-sync:javaDocReleaseJar",
        ":trackit-all:javaDocReleaseJar",
    )
}

tasks.register<ArchiveReleaseMappingsTask>("archiveReleaseMappings") {
    group = "distribution"
    description = "Copies R8 release mappings into local release storage."
    repositoryRoot.set(layout.projectDirectory)
    version.set(catalog.findVersion("trackit").get().requiredVersion)
    commitSha.set(
        listOfNotNull(
            (findProperty("trackitCommitSha") as String?)?.takeIf { it.isNotBlank() },
            System.getenv("TRACKIT_COMMIT_SHA")?.takeIf { it.isNotBlank() },
            System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() },
            System.getenv("CI_COMMIT_SHA")?.takeIf { it.isNotBlank() },
        ).firstOrNull() ?: "unknown",
    )

    dependsOn(
        ":trackit-geo:assembleRelease",
        ":trackit-core:assembleRelease",
        ":trackit-maps:assembleRelease",
        ":trackit-snap:assembleRelease",
        ":trackit-sync:assembleRelease",
    )
}
