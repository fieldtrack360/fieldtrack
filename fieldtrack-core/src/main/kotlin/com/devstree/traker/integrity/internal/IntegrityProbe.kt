package com.devstree.traker.integrity.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import com.devstree.traker.SecurityConfig
import com.devstree.traker.integrity.IntegrityFinding
import com.devstree.traker.integrity.IntegritySignal
import com.devstree.traker.license.LicenseEnvironment

/**
 * What a probe saw, before any policy is applied.
 *
 * Probes answer facts; [IntegrityEvaluator] answers consequences. Keeping the two apart
 * is what makes the whole layer testable on the JVM with fakes: the policy matrix is
 * exercised without an Android framework in sight.
 */
internal data class IntegrityObservation(
    val signal: IntegritySignal,
    val detail: String,
    val confidence: Int = IntegrityFinding.FULL_CONFIDENCE,
)

/**
 * One check.
 *
 * Contract: **never throws**. A probe that cannot answer returns an empty list. This runs
 * on the `ready()` path, and an SDK that fails to start because a `/proc` read was denied
 * by an OEM kernel is worse than one that misses a signal.
 */
internal fun interface IntegrityProbe {
    fun observe(config: SecurityConfig): List<IntegrityObservation>
}

/** Wraps [IntegrityProbe.observe] so a throwing probe degrades to "saw nothing". */
internal fun IntegrityProbe.observeSafely(config: SecurityConfig): List<IntegrityObservation> =
    runCatching { observe(config) }.getOrDefault(emptyList())

/**
 * The one definition of "this is a development build".
 *
 * Deliberately delegates to [LicenseEnvironment.hasGetTaskAllow], the same predicate the
 * license gate waives on. Two definitions of "debug" in one SDK is how a build ends up
 * licensed-but-unguarded, or guarded-but-unlicensed.
 *
 * A repackaged APK can set `android:debuggable="true"` and claim this waiver. Re-signing
 * to do so changes the signing certificate; binding the waiver to a certificate hash
 * carried in the license token closes that door and is tracked separately — it is a
 * token-format change (docs/DEVICE-INTEGRITY-PLAN.md §2.3).
 */
internal object IntegrityEnvironment {
    fun isWaived(context: Context): Boolean = LicenseEnvironment.hasGetTaskAllow(context)

    /** `true` for packages installed as part of the system image. */
    fun isSystemPackage(info: ApplicationInfo): Boolean =
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}

/**
 * Reads text files from `/proc`. Injected so [probes.HookingProbe] is a pure function of
 * file contents in tests, replayed from captured fixtures rather than from the machine
 * running the test suite.
 */
internal fun interface ProcReader {
    /** Contents of [path], or `null` if unreadable. Must not throw. */
    fun read(path: String): String?

    companion object {
        val System: ProcReader = ProcReader { path ->
            runCatching { java.io.File(path).takeIf { it.canRead() }?.readText() }.getOrNull()
        }
    }
}

/** Lists entries of a directory. Same injection rationale as [ProcReader]. */
internal fun interface ProcDirLister {
    fun list(path: String): List<String>

    companion object {
        val System: ProcDirLister = ProcDirLister { path ->
            runCatching { java.io.File(path).list()?.toList().orEmpty() }.getOrDefault(emptyList())
        }
    }
}

/** Attempts a loopback TCP connect. Injected so tests never open a socket. */
internal fun interface LoopbackProbe {
    /** `true` when something is listening on `127.0.0.1:[port]`. Must not throw. */
    fun isOpen(port: Int): Boolean

    companion object {
        const val TIMEOUT_MS: Int = 120

        val System: LoopbackProbe = LoopbackProbe { port ->
            runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), TIMEOUT_MS)
                    true
                }
            }.getOrDefault(false)
        }
    }
}
