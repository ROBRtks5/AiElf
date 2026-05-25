package com.example.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_profile")

data class UserProfile(
    val name: String,
    val age: String,
    val location: String,
    val job: String,
    val hobbies: String,
    val regimen: String
)

class ProfileManager(private val context: Context) {
    companion object {
        val NAME = stringPreferencesKey("name")
        val AGE = stringPreferencesKey("age")
        val LOCATION = stringPreferencesKey("location")
        val JOB = stringPreferencesKey("job")
        val HOBBIES = stringPreferencesKey("hobbies")
        val REGIMEN = stringPreferencesKey("regimen")
    }

    val userProfileFlow: Flow<UserProfile> = context.dataStore.data
        .map { preferences ->
            UserProfile(
                name = preferences[NAME] ?: "Братишка",
                age = preferences[AGE] ?: "38 лет",
                location = preferences[LOCATION] ?: "Россия, Ростовская область, город Гуково",
                job = preferences[JOB] ?: "Банк, поддержка первой линии в отделе инвестиций, удаленка.",
                hobbies = preferences[HOBBIES] ?: "Эндуро и стрит на велосипеде (Rocky Mountain Altitude), трейдинг (ОФЗ, фьючерс S1M6), генерация в Suno AI, изучение SQL.",
                regimen = preferences[REGIMEN] ?: "Протокол «Монолит» (тренировки, диета с цельными яйцами)."
            )
        }

    suspend fun saveProfile(profile: UserProfile) {
        context.dataStore.edit { preferences ->
            preferences[NAME] = profile.name
            preferences[AGE] = profile.age
            preferences[LOCATION] = profile.location
            preferences[JOB] = profile.job
            preferences[HOBBIES] = profile.hobbies
            preferences[REGIMEN] = profile.regimen
        }
    }
}
