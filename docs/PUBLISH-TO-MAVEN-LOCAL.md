# TrackIt SDK Publishing Guide

This document explains how to build, verify, and publish the TrackIt SDK artifacts for the sample app or another Android project. The complete R8 policy is in [PROGUARD-SETUP.md](PROGUARD-SETUP.md).

## Local Development Workflow

To test SDK changes without manual AAR management, verify the release artifacts and publish them to Maven Local.

### 1. Verify Release Obfuscation

Run the artifact audit before publishing:

```bash
./gradlew verifyReleaseObfuscation
```

The task builds all release AARs and fails when supported API names disappear, implementation packages are not shortened, known release log messages remain, a private R8 mapping is embedded, a source JAR is generated, or a Javadoc JAR contains source entries.

### 2. Publish to Maven Local

Run this command from the repository root to publish all modules to `~/.m2/repository`:

```bash
./gradlew publishToMavenLocal
```

Maven publishing depends on `verifyReleaseObfuscation`, so it cannot bypass the audit. It generates:

- R8-obfuscated AARs for `geo`, `core`, `maps`, `snap`, and `sync`.
- An empty umbrella AAR for `all`, with the other modules as transitive dependencies.
- POM files containing transitive dependency metadata.
- Javadoc JARs containing rendered public API documentation, with no source entries.
- No source JARs.

The same publish flow also runs `archiveReleaseMappings`, which copies each module's
`mapping.txt`, `seeds.txt`, `usage.txt`, and a `manifest.txt` into
`build/release-mappings/<version>/<module>/` for restricted release storage.

### 3. Configure Consumer App
In the consuming app's `settings.gradle.kts`, ensure `mavenLocal()` is included:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

Then, depend on the umbrella artifact in `build.gradle.kts` (or using the Version Catalog):

```kotlin
dependencies {
    implementation("com.devstree.trackit:trackit-all:0.1.0")
}
```

## Versioning

The TrackIt SDK uses [Semantic Versioning](https://semver.org/) (Major.Minor.Patch).

### How to Update the Version
The source of truth for the SDK version is [gradle/libs.versions.toml](../gradle/libs.versions.toml).

1.  Open `gradle/libs.versions.toml`.
2.  Locate the `trackit` variable in the `[versions]` section:
    ```toml
    [versions]
    trackit = "0.1.0"
    ```
3.  Change the version string to your new version.
4.  Run `./gradlew publishToMavenLocal` (or `publish`) to build artifacts with the new version.

### When to Update the Version
- **Major (1.0.0)**: When you make incompatible API changes (e.g., changing a method signature in `TrackIt` or removing a property from `TrackItConfig`).
- **Minor (0.2.0)**: When you add functionality in a backwards-compatible manner (e.g., adding a new `AccuracyProfile` or a new field in `TrackPoint`).
- **Patch (0.1.1)**: When you make backwards-compatible bug fixes.

## JitPack Publishing

JitPack builds the SDK directly from the GitHub repository.

### 1. Configuration
The repository includes a [jitpack.yml](file:///home/user/Mitesh/Devstree/trackit/jitpack.yml) to ensure JitPack uses **JDK 17**, which is required by Gradle 9 and AGP 9.

```yaml
jdk:
  - openjdk17
```

### 2. Consuming via JitPack
In the consuming app's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then, add the dependency using the GitHub coordinates. JitPack maps the repository to the `com.github.devstree-prog` group:

```kotlin
dependencies {
    // Umbrella artifact (includes all modules)
    implementation("com.github.devstree-prog:trackit-all:v0.1.1-alpha01")
    
    // Or individual modules
    implementation("com.github.devstree-prog:trackit-core:v0.1.1-alpha01")
}
```

## Production / Remote Publishing

To push artifacts to a remote Maven repository, provide the destination URL and credentials. Prefer the environment variables `TRACKIT_MAVEN_URL`, `TRACKIT_MAVEN_USER`, and `TRACKIT_MAVEN_TOKEN` in CI so credentials never enter shell history.

```bash
./gradlew publish \
  -PtrackitMavenUrl=https://maven.pkg.github.com/devstree-prog/trackit \
  -PtrackitMavenUser=YOUR_USER \
  -PtrackitMavenToken=YOUR_TOKEN
```

## Implementation Details

The publishing logic is centralized in [gradle/publish.gradle.kts](../gradle/publish.gradle.kts). It handles:
- Coordinate mapping (`groupId`, `artifactId`, `version`).
- R8 minification for release variants (IP protection).
- Javadoc JAR generation.
- POM metadata (licenses, developers, SCM).
- Mandatory release-obfuscation verification before every Maven publish task.

R8 mapping files are private release artifacts under `trackit-<module>/build/outputs/mapping/release/mapping.txt`. Archive them in restricted release storage; never put them in the public Maven repository.
