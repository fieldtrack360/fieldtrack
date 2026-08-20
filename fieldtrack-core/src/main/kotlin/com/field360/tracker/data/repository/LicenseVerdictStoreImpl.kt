package com.field360.tracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.field360.tracker.domain.model.CachedVerdict
import com.field360.tracker.domain.model.SignedVerdict
import com.field360.tracker.domain.repository.LicenseVerdictStore
import com.field360.tracker.domain.repository.VerdictAuthenticator
import java.security.MessageDigest
import kotlinx.coroutines.flow.first

private val Context.licenseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fieldtrack_license",
)

/**
 * The last verdict, persisted whole.
 *
 * What is stored is the **entire signed document including its signature**, and it is
 * re-verified on the way back out. That is what makes the file tamper-proof without any
 * encryption: editing `"revoked"` to `"active"` on disk breaks the signature, the read
 * returns null, and the next check goes to the network. A device owner with root is the
 * expected adversary, so a store that trusted its own contents would be a way around the
 * whole layer.
 *
 * Entries are keyed by a digest of the token. A host that swaps its licence gets a cache
 * miss rather than the previous licence's answer, which is the difference between
 * "I updated my licence" working and it turning into a bug report a day later.
 */
internal class LicenseVerdictStoreImpl(
    private val context: Context,
    private val authenticator: VerdictAuthenticator,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LicenseVerdictStore {

    override suspend fun write(token: String, verdict: SignedVerdict) {
        runCatching {
            context.licenseDataStore.edit { prefs ->
                prefs[rawKey(token)] = verdict.raw
                prefs[atKey(token)] = nowMs()
            }
        }
    }

    /**
     * The whole read sits inside `runCatching` on purpose. A corrupt DataStore file throws
     * `IOException`, and this runs behind a check whose contract is to fail open; leaving
     * the call outside the guard would turn a bad write during a power cut into a crash on
     * a background thread.
     *
     * The nonce is deliberately not re-checked. It proved freshness when the response
     * arrived and can prove nothing about a value read back from disk; staleness is
     * [CachedVerdict.isStale]'s job instead.
     */
    override suspend fun read(token: String): CachedVerdict? = runCatching {
        val prefs = context.licenseDataStore.data.first()
        val raw = prefs[rawKey(token)] ?: return null
        val storedAt = prefs[atKey(token)] ?: return null
        val verdict = authenticator.authenticate(raw, token, sentNonce = null) ?: return null
        CachedVerdict(verdict, storedAt, nowMs)
    }.getOrNull()

    override suspend fun clear(token: String) {
        runCatching {
            context.licenseDataStore.edit { prefs ->
                prefs.remove(rawKey(token))
                prefs.remove(atKey(token))
            }
        }
    }

    private fun rawKey(token: String) = stringPreferencesKey("verdict_raw_${token.digest()}")

    private fun atKey(token: String) = longPreferencesKey("verdict_at_${token.digest()}")

    /**
     * The key is derived rather than the token itself: preferences keys end up in a file
     * on disk, and an access key sitting there in the clear is one `adb pull` from being
     * someone else's access key.
     */
    private fun String.digest(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(trim().toByteArray(Charsets.UTF_8))
            .take(TOKEN_KEY_BYTES)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TOKEN_KEY_BYTES = 8
    }
}
