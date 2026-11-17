package com.example.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.DarkModePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.snrThemeDataStore by preferencesDataStore(name = "snr_chat_prefs")

class ThemePreferenceStore(private val context: Context) {
    private val darkModeKey = stringPreferencesKey("dark_mode_preference")

    @Volatile
    var cachedPreference: DarkModePreference = DarkModePreference.DARK
        private set

    val darkModePreference: Flow<DarkModePreference> =
        context.snrThemeDataStore.data.map { prefs ->
            DarkModePreference.fromRaw(prefs[darkModeKey] ?: DarkModePreference.DARK.name).also {
                cachedPreference = it
            }
        }.flowOn(Dispatchers.IO)

    suspend fun preload() {
        runCatching {
            darkModePreference.first()
        }
    }

    suspend fun setDarkModePreference(pref: DarkModePreference) {
        cachedPreference = pref
        context.snrThemeDataStore.edit { prefs ->
            prefs[darkModeKey] = pref.name
        }
    }
}
