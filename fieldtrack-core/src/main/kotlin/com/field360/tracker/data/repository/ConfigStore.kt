package com.field360.tracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.field360.tracker.TrackerConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.trackItDataStore: DataStore<Preferences> by preferencesDataStore(name = "trackit_config")

/**
 * Config persistence across launches.
 *
 * Decoding is forward-compatible on purpose: a config written by a newer SDK and read
 * by an older one drops the unknown keys with a log rather than failing to start. A
 * library that bricks itself on downgrade is not shippable (EC-124).
 */
internal class ConfigStore(
    private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The config as last loaded or saved, without touching disk.
     *
     * Exists for the one caller that cannot suspend: `TrackerSync.configure()` runs on the
     * host's own thread and needs [TrackerConfig.baseUrl] to resolve a path against. `null`
     * until `ready()` has run, which is the honest answer at that point — the alternative is
     * a blocking disk read on a host thread to say "nothing yet".
     */
    @Volatile
    var cached: TrackerConfig? = null
        private set

    suspend fun load(): TrackerConfig? {
        val raw = context.trackItDataStore.data.first()[KEY_CONFIG] ?: return null
        return runCatching { json.decodeFromString<TrackerConfig>(raw) }.getOrNull()
            ?.also { cached = it }
    }

    suspend fun save(config: TrackerConfig) {
        val encoded = json.encodeToString(config)
        context.trackItDataStore.edit { it[KEY_CONFIG] = encoded }
        cached = config
    }

    suspend fun clear() {
        context.trackItDataStore.edit { it.remove(KEY_CONFIG) }
        cached = null
    }

    fun encode(config: TrackerConfig): String = json.encodeToString(config)

    private companion object {
        val KEY_CONFIG = stringPreferencesKey("config_json")
    }
}
