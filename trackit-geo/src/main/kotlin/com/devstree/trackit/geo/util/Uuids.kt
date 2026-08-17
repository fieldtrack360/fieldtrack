package com.devstree.trackit.geo.util

import java.security.MessageDigest

/**
 * Deterministic point identity.
 *
 * `(sessionId, elapsedRealtimeNanos)` is unique per fix and stable across restarts, so
 * two writers racing to store the same fix — the stream and the 15-minute backstop —
 * produce the same uuid and the second insert is ignored rather than duplicated
 * (EC-82).
 *
 * Determinism also matters for fixture replay: the same recording must yield the same
 * ids on every run, which rules out random UUIDs.
 */
public object Uuids {

    public fun forFix(sessionId: String, elapsedRealtimeNanos: Long): String =
        sha1Hex("$sessionId:$elapsedRealtimeNanos")

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val v = byte.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
