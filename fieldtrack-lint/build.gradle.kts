import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Custom lint rules, shipped inside the AARs.
 *
 * A plain JVM module, not an Android one: lint checks run on the host's build machine
 * against UAST and XML, and never on a device. `fieldtrack-core` wires it in with
 * `lintPublish`, which packages this jar into the AAR so the rules fire in the **host
 * app's** build — the only place they matter, because the thing being guarded against is
 * an integrator shipping a release with the integrity layer switched off.
 *
 * Deliberately not published to Maven on its own: it has no API a host would call.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.javaTarget.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // compileOnly, per lint's own guidance: the lint runtime supplies these at analysis
    // time, and bundling them would put a second copy of the tooling inside every AAR.
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    testImplementation(kotlin("stdlib"))
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit)
}

tasks.jar {
    manifest {
        // How lint finds the registry inside the jar. Without it the checks are packaged
        // and silently never run — the failure mode this attribute exists to prevent.
        attributes("Lint-Registry-v2" to "com.devstree.traker.lint.FieldTrackIssueRegistry")
    }
}
