package com.devstree.trackit.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.devstree.trackit.TrackItConfig
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

    suspend fun load(): TrackItConfig? {
        val raw = context.trackItDataStore.data.first()[KEY_CONFIG] ?: return null
        return runCatching { json.decodeFromString<TrackItConfig>(raw) }.getOrNull()
    }

    suspend fun save(config: TrackItConfig) {
        val encoded = json.encodeToString(config)
        context.trackItDataStore.edit { it[KEY_CONFIG] = encoded }
    }

    suspend fun clear() {
        context.trackItDataStore.edit { it.remove(KEY_CONFIG) }
    }

    fun encode(config: TrackItConfig): String = json.encodeToString(config)

    private companion object {
        val KEY_CONFIG = stringPreferencesKey("config_json")
    }
}
