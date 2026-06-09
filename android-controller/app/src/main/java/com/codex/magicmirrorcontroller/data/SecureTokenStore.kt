package com.codex.magicmirrorcontroller.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureTokenStore(context: Context) {
    private val appContext = context.applicationContext

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            "magic_mirror_secure_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun readToken(): String? {
        return runCatching {
            preferences.getString(TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun saveToken(token: String) {
        runCatching {
            preferences.edit().putString(TOKEN_KEY, token).apply()
        }
    }

    fun clearToken() {
        runCatching {
            preferences.edit().remove(TOKEN_KEY).apply()
        }
    }

    private companion object {
        const val TOKEN_KEY = "bearer_token"
    }
}
