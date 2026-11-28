package com.example.inchat.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "user_preferences")

class UserPreferencesManager(private val context: Context) {
    companion object {
        private val NICKNAME_KEY = stringPreferencesKey("nickname")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
    }

    val nicknameFlow: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[NICKNAME_KEY] }

    val themeModeFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[THEME_MODE_KEY] ?: THEME_DARK }

    suspend fun saveNickname(nickname: String) {
        context.dataStore.edit { preferences -> preferences[NICKNAME_KEY] = nickname }
    }

    suspend fun saveThemeMode(mode: String) {
        require(mode == THEME_DARK || mode == THEME_LIGHT)
        context.dataStore.edit { preferences -> preferences[THEME_MODE_KEY] = mode }
    }
}
