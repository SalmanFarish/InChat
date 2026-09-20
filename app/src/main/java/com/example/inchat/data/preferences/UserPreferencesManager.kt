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
    }

    val nicknameFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[NICKNAME_KEY]
        }

    suspend fun saveNickname(nickname: String) {
        context.dataStore.edit { preferences ->
            preferences[NICKNAME_KEY] = nickname
        }
    }
}