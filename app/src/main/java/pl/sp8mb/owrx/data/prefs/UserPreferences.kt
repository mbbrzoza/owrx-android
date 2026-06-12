package pl.sp8mb.owrx.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pl.sp8mb.owrx.session.DesiredState
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("owrx_prefs")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val LAST_SERVER = longPreferencesKey("last_server_id")
        val DESIRED = stringPreferencesKey("desired_state")
        val FAVORITES = stringPreferencesKey("favorites")
    }

    val lastServerId: Flow<Long?> = context.dataStore.data.map { it[Keys.LAST_SERVER] }

    suspend fun setLastServer(id: Long) {
        context.dataStore.edit { it[Keys.LAST_SERVER] = id }
    }

    val desiredState: Flow<DesiredState?> = context.dataStore.data.map { prefs ->
        prefs[Keys.DESIRED]?.let {
            try {
                json.decodeFromString<DesiredState>(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun saveDesiredState(state: DesiredState) {
        context.dataStore.edit { it[Keys.DESIRED] = json.encodeToString(state) }
    }

    val favorites: Flow<List<Favorite>> = context.dataStore.data.map { prefs ->
        prefs[Keys.FAVORITES]?.let {
            try {
                json.decodeFromString<List<Favorite>>(it)
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }

    suspend fun saveFavorites(favorites: List<Favorite>) {
        context.dataStore.edit { it[Keys.FAVORITES] = json.encodeToString(favorites) }
    }
}

/**
 * A bookmark remembered across profiles — carries the profile it belongs to,
 * so tapping it can switch the receiver to that band first.
 */
@kotlinx.serialization.Serializable
data class Favorite(
    val name: String,
    val freqHz: Long,
    val modulation: String? = null,
    val profileId: String? = null,
)
