package com.field360.tracker.integrity.probes

import android.os.Build
import android.os.Debug
import com.field360.tracker.SecurityConfig
import com.field360.tracker.integrity.IntegrityFinding
import com.field360.tracker.integrity.IntegritySignal
import com.field360.tracker.integrity.internal.IntegrityObservation
import com.field360.tracker.integrity.internal.IntegrityProbe
import com.field360.tracker.integrity.internal.LoopbackProbe
import com.field360.tracker.integrity.internal.ProcDirLister
import com.field360.tracker.integrity.internal.ProcReader

/**
 * Frida, Xposed and attached debuggers.
 *
 * **Weighted, not boolean.** No single indicator here is proof: a thread called `gmain`
 * belongs to GLib as much as to Frida's agent, a listener on 27042 could be anything, and
 * `TracerPid` is non-zero under a profiler. Any one of them alone would produce false
 * positives on real field devices, and this signal blocks by default — so indicators are
 * weighted and the finding is raised only at [THRESHOLD]. The summed weight travels with
 * the finding as its confidence, so a host reading its logs can tell "a port answered"
 * from "the agent is mapped into our address space".
 *
 * Every read is of `/proc/self` — the process's own view of itself. No permission, no
 * package visibility, nothing that shows up in a Play data-safety form.
 *
 * All three I/O ports are injected, so the whole weighting is replayed from captured
 * fixtures in tests and no test opens a socket or depends on the machine it runs on.
 */
internal class HookingProbe(
    private val procReader: ProcReader = ProcReader.System,
    private val dirLister: ProcDirLister = ProcDirLister.System,
    private val loopback: LoopbackProbe = LoopbackProbe.System,
    private val isEmulator: Boolean = looksLikeEmulator(),
) : IntegrityProbe {

    override fun observe(config: SecurityConfig): List<IntegrityObservation> = buildList {
        var confidence = 0
        val evidence = mutableListOf<String>()

        if (mapsMentionHookingLibrary()) {
            confidence += WEIGHT_MAPPED_LIBRARY
            evidence += "hooking library mapped"
        }

        if (threadNamesLookLikeFrida()) {
            confidence += WEIGHT_THREAD_NAMES
            evidence += "agent thread names"
        }

        // Emulators are excluded from the port scan only. CI images and dev emulators run
        // all sorts of loopback tooling, and a false BLOCK on the build farm would be
        // indistinguishable from the SDK being broken.
        if (!isEmulator && fridaPortAnswers()) {
            confidence += WEIGHT_OPEN_PORT
            evidence += "frida port open"
        }

        if (xposedOnClasspath()) {
            confidence += WEIGHT_XPOSED
            evidence += "xposed present"
        }

        val tracerPid = tracerPid()
        if (tracerPid != 0) {
            confidence += WEIGHT_TRACER
            evidence += "TracerPid=$tracerPid"
        }

        if (confidence >= THRESHOLD) {
            add(
                IntegrityObservation(
                    signal = IntegritySignal.HOOKING_FRAMEWORK_DETECTED,
                    detail = evidence.take(IntegrityFinding.MAX_DETAIL_ITEMS).joinToString(", "),
                    confidence = confidence.coerceAtMost(IntegrityFinding.FULL_CONFIDENCE),
                ),
            )
        }

        // Reported independently of the weighted verdict above. A debugger attached to a
        // release build is its own fact, and the host may want to see it even when nothing
        // else fired.
        val javaDebugger = Debug.isDebuggerConnected()
        if (tracerPid != 0 || javaDebugger) {
            add(
                IntegrityObservation(
                    signal = IntegritySignal.DEBUGGER_ATTACHED,
                    detail = if (javaDebugger) "java debugger connected" else "native tracer pid $tracerPid",
                ),
            )
        }
    }

    private fun mapsMentionHookingLibrary(): Boolean {
        val maps = procReader.read("/proc/self/maps") ?: return false
        return MAPPED_LIBRARY_MARKERS.any { maps.contains(it, ignoreCase = true) }
    }

    /**
     * Frida's agent runs a GLib main loop and a JS thread inside the target process, and
     * names them. Reading `comm` for each task is the cheapest way to see them: a dozen
     * one-line files, no allocation worth counting.
     */
    private fun threadNamesLookLikeFrida(): Boolean {
        val tasks = dirLister.list("/proc/self/task")
        if (tasks.isEmpty()) return false

        val names = tasks.mapNotNull { tid ->
            procReader.read("/proc/self/task/$tid/comm")?.trim()?.lowercase()
        }
        return THREAD_NAME_MARKERS.any { marker -> names.any { it == marker } }
    }

    private fun fridaPortAnswers(): Boolean = FRIDA_PORTS.any { loopback.isOpen(it) }

    private fun xposedOnClasspath(): Boolean = XPOSED_CLASSES.any { className ->
        runCatching { Class.forName(className, false, javaClass.classLoader) }.isSuccess
    }

    /** `/proc/self/status` line `TracerPid:\t0` — non-zero means something is ptrace-attached. */
    private fun tracerPid(): Int {
        val status = procReader.read("/proc/self/status") ?: return 0
        val line = status.lineSequence().firstOrNull { it.startsWith("TracerPid:") } ?: return 0
        return line.substringAfter(':').trim().toIntOrNull() ?: 0
    }

    private companion object {
        const val WEIGHT_MAPPED_LIBRARY = 60
        const val WEIGHT_THREAD_NAMES = 40
        const val WEIGHT_OPEN_PORT = 30
        const val WEIGHT_XPOSED = 40
        const val WEIGHT_TRACER = 50

        /** Reachable by any two indicators, never by one alone. */
        const val THRESHOLD = 60

        val MAPPED_LIBRARY_MARKERS = listOf(
            "frida-agent",
            "frida-gadget",
            "libfrida",
            "libgadget",
            "re.frida.server",
            "libsubstrate",
            "libxposed",
            "liblsposed",
        )

        val THREAD_NAME_MARKERS = listOf(
            "gmain",
            "gdbus",
            "gum-js-loop",
            "pool-frida",
            "linjector",
        )

        val FRIDA_PORTS = listOf(27042, 27043)

        val XPOSED_CLASSES = listOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "org.lsposed.lspd.core.Main",
        )

        fun looksLikeEmulator(): Boolean {
            val fingerprint = Build.FINGERPRINT.orEmpty()
            return fingerprint.startsWith("generic") ||
                fingerprint.contains("sdk_gphone") ||
                fingerprint.contains("emulator") ||
                Build.HARDWARE.orEmpty() in listOf("goldfish", "ranchu", "cutf_cvm")
        }
    }
}
