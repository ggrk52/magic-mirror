package com.codex.magicmirrorcontroller.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "server_config")

class ServerConfigRepository(private val context: Context) {
    val savedEndpoint: Flow<SavedServerEndpoint?> =
        context.dataStore.data.map { preferences ->
            val host = preferences[HOST_KEY]
            val port = preferences[PORT_KEY]

            if (host.isNullOrBlank() || port == null) {
                null
            } else {
                SavedServerEndpoint(host = host, port = port)
            }
        }

    suspend fun saveEndpoint(config: ServerConfig) {
        context.dataStore.edit { preferences ->
            preferences[HOST_KEY] = config.host
            preferences[PORT_KEY] = config.port
            preferences.remove(TOKEN_KEY)
        }
    }

    companion object {
        private val HOST_KEY = stringPreferencesKey("host")
        private val PORT_KEY = intPreferencesKey("port")
        private val TOKEN_KEY = stringPreferencesKey("token")
    }
}
