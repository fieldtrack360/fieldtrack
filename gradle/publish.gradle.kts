import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

/**
 * Publishing, in one place.
 *
 * Applied by each publishable module with:
 *
 * ```kotlin
 * apply(from = rootProject.file("gradle/publish.gradle.kts"))
 * ```
 *
 * **This is a script plugin, not a convention plugin, and not a `build-logic` composite
 * build.** BUILD.md records that the composite was removed and that every module
 * configures itself — this does not reintroduce it. What it does refuse is the other
 * option, which was six near-identical forty-line blocks that must agree about group,
 * version, POM metadata and repository credentials. Six copies of a thing that must agree
 * eventually do not, and the symptom would be one artifact published under the wrong
 * coordinates, which is only discovered by whoever tries to consume it.
 *
 * ## What gets published
 *
 * The `release` variant of each Android library plus a javadoc jar (R-46).
 * **No sources jar, anywhere** — the release AARs are R8-obfuscated for IP protection
 * (BUILD.md §5.6), and a -sources.jar next to
 * an obfuscated artifact hands over exactly what the obfuscation hides. This reverses
 * the earlier decision that shipped sources for IDE navigation; the trade was made
 * knowingly, and flipping it back is one `withSourcesJar()` per module if the SDK is
 * ever distributed to consumers who are allowed the code.
 *
 * AGP generates rendered Kotlin API documentation for the javadoc classifier. The
 * archive contains HTML and assets, never `.kt` or `.java` source entries; the release
 * verifier enforces that distinction. Public API signatures and KDoc are intentionally
 * discoverable, while implementation source remains private.
 *
 * ## Where it goes
 *
 * `publishToMavenLocal` always works and needs no configuration — that is the loop for
 * testing the npm package against a local build.
 *
 * A remote repository is added **only if** its URL is configured, so a developer with no
 * credentials gets a working local publish rather than a configuration error:
 *
 * ```bash
 * ./gradlew publish \
 *   -PtrakerMavenUrl=https://maven.pkg.github.com/fieldtrack360/fieldtrack \
 *   -PtrakerMavenUser=… -PtrakerMavenToken=…
 * ```
 *
 * or via `TRAKER_MAVEN_URL` / `TRAKER_MAVEN_USER` / `TRAKER_MAVEN_TOKEN` in the
 * environment, which is what CI should use.
 *
 * **No credential has a default and none is read from a file in this repository.** That
 * is not a general principle being restated for its own sake: a live Google Maps API key
 * was committed to this repo once, is still in its history, and is unfixable by anything
 * short of rotation (BUILD.md §1). A publishing token has strictly more blast radius than
 * that key did.
 */

apply(plugin = "maven-publish")

val catalog = extensions
    .getByType(VersionCatalogsExtension::class.java)
    .named("libs")

// Respect properties passed by JitPack or CLI, with defaults from the catalog.
group = "com.github.fieldtrack360.fieldtrack"
version = (findProperty("version") as? String)?.takeIf { it != "unspecified" } ?: catalog.findVersion("traker").get().requiredVersion

/** Property first, environment second. CI sets the environment; a developer sets neither. */
fun setting(property: String, environment: String): String? =
    (findProperty(property) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environment)?.takeIf { it.isNotBlank() }

val remoteUrl = setting("trakerMavenUrl", "TRAKER_MAVEN_URL")
val remoteUser = setting("trakerMavenUser", "TRAKER_MAVEN_USER")
val remoteToken = setting("trakerMavenToken", "TRAKER_MAVEN_TOKEN")

// Every module declares `android { publishing { singleVariant("release") } }` locally.
// A script plugin is compiled against Gradle's API, so AGP's `LibraryExtension` and
// `singleVariant` do not resolve here. What stays centralized is what must not drift:
// coordinates, POM metadata, repositories, credentials, and the no-sources policy.

afterEvaluate {
    extensions.configure(PublishingExtension::class.java) {
        publications {
            create<MavenPublication>("release") {
                // `release`, never `debug`. A debug AAR carries no ProGuard consumer
                // rules a host will honour and is not the artifact anyone means.
                from(components.getByName("release"))

                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()

                pom {
                    name.set(project.name)
                    description.set(pomDescription(project.name))
                    url.set("https://github.com/fieldtrack360/fieldtrack")

                    licenses {
                        license {
                            name.set("Proprietary")
                            comments.set("All rights reserved. Internal distribution only.")
                        }
                    }
                    developers {
                        developer {
                            name.set("FieldTrack360")
                        }
                    }
                    scm {
                        url.set("https://github.com/fieldtrack360/fieldtrack")
                        connection.set("scm:git:https://github.com/fieldtrack360/fieldtrack.git")
                    }
                }
            }
        }

        repositories {
            if (remoteUrl != null) {
                maven {
                    name = "traker"
                    setUrl(remoteUrl)
                    if (remoteUser != null && remoteToken != null) {
                        credentials {
                            username = remoteUser
                            password = remoteToken
                        }
                    }
                }
            }
            // mavenLocal is always available through `publishToMavenLocal`; it is
            // deliberately not declared here, because declaring it would put a
            // `publishMavenPublicationToMavenLocalRepository` task alongside the built-in
            // one and make "did it publish locally" ambiguous.
        }
    }
}

// No Maven publish may bypass the artifact-level security audit.
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(rootProject.tasks.named("verifyReleaseObfuscation"))
    dependsOn(rootProject.tasks.named("archiveReleaseMappings"))
}

/** Kept here rather than in six build files, for the reason the whole file exists. */
fun pomDescription(module: String): String = when (module) {
    "fieldtrack-geo" ->
        "Traker engine — Kalman filter, acceptance pipeline, motion state machine and " +
            "plotting. Pure Kotlin source packaged as an R8-obfuscated Android AAR."
    "fieldtrack-core" ->
        "Traker — background location tracking and track plotting for Android. " +
            "The capture stack, Room storage and the public SDK surface."
    "fieldtrack-maps" -> "Traker — Google Maps rendering for tracks built by fieldtrack-core."
    "fieldtrack-sync" -> "Traker — optional HTTP upload with a retry queue."
    "fieldtrack-snap" -> "Traker — optional road snapping against an OSRM server."
    "fieldtrack" ->
        "Traker — umbrella artifact. Depending on this pulls in every Traker module " +
            "(core, geo, maps, sync, snap) transitively; no code of its own."
    else -> "Traker — $module"
}
