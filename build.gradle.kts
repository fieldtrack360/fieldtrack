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
version = catalog.findVersion("traker").get().requiredVersion

subprojects {
    group = "com.github.fieldtrack360.fieldtrack"
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
    version.set(catalog.findVersion("traker").get().requiredVersion)
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
