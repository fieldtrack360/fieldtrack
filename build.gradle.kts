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

/**
 * Coordinates, from `-Pgroup`/`-Pversion` when the build is driven by CI, otherwise from
 * the catalog.
 *
 * JitPack invokes the build as `-Pgroup=… -Pversion=v…`, and both used to be discarded:
 * the group was hardcoded, and `findProperty("version")` reads the *project's* version,
 * which Gradle only sets from the command line on the root project. Every module saw
 * `unspecified`, fell back to the catalog, and published POMs whose version disagreed
 * with the tag being built. `providers.gradleProperty` reads the property itself, from
 * any project, and is configuration-cache safe.
 */
val publishGroup: String = providers.gradleProperty("group").orNull
    ?.takeIf { it.isNotBlank() }
    ?: "com.github.fieldtrack360.fieldtrack"
val publishVersion: String = providers.gradleProperty("version").orNull
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
    ?: catalog.findVersion("traker").get().requiredVersion

group = publishGroup
version = publishVersion

subprojects {
    group = publishGroup
    version = publishVersion
}

tasks.register<VerifyReleaseObfuscationTask>("verifyReleaseObfuscation") {
    group = "verification"
    description = "Builds and audits every Tracker release artifact for obfuscation leaks."
    repositoryRoot.set(layout.projectDirectory)

    dependsOn(
        ":fieldtrack-geo:assembleRelease",
        ":fieldtrack-core:assembleRelease",
        ":fieldtrack-maps:assembleRelease",
        ":fieldtrack-snap:assembleRelease",
        ":fieldtrack-sync:assembleRelease",
        ":fieldtrack:assembleRelease",
        ":fieldtrack-geo:javaDocReleaseJar",
        ":fieldtrack-core:javaDocReleaseJar",
        ":fieldtrack-maps:javaDocReleaseJar",
        ":fieldtrack-snap:javaDocReleaseJar",
        ":fieldtrack-sync:javaDocReleaseJar",
        ":fieldtrack:javaDocReleaseJar",
    )
}

tasks.register<VerifyReleaseIntegrityTask>("verifyReleaseIntegrity") {
    group = "verification"
    description = "Audits the release security posture: R8 on, no hardcoded debuggable, integrity defaults intact."
    repositoryRoot.set(layout.projectDirectory)
}

// Publishing an artifact with the security layer disabled is the failure this task exists
// to prevent, so it runs before anything leaves the machine rather than as a separate step
// somebody has to remember.
subprojects {
    tasks.matching { it.name.startsWith("publish") }.configureEach {
        dependsOn(rootProject.tasks.named("verifyReleaseIntegrity"))
    }
}

tasks.register<ArchiveReleaseMappingsTask>("archiveReleaseMappings") {
    group = "distribution"
    description = "Copies R8 release mappings into local release storage."
    repositoryRoot.set(layout.projectDirectory)
    version.set(publishVersion)
    commitSha.set(
        listOfNotNull(
            (findProperty("trakerCommitSha") as String?)?.takeIf { it.isNotBlank() },
            System.getenv("TRAKER_COMMIT_SHA")?.takeIf { it.isNotBlank() },
            System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() },
            System.getenv("CI_COMMIT_SHA")?.takeIf { it.isNotBlank() },
        ).firstOrNull() ?: "unknown",
    )

    dependsOn(
        ":fieldtrack-geo:assembleRelease",
        ":fieldtrack-core:assembleRelease",
        ":fieldtrack-maps:assembleRelease",
        ":fieldtrack-snap:assembleRelease",
        ":fieldtrack-sync:assembleRelease",
    )
}
