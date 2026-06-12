package pl.sp8mb.owrx.data.prefs

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup/restore of the favorites list as a JSON file in the app's external
 * Documents dir (reachable via a file manager or adb for off-device backup).
 */
@Singleton
class FavoritesIO @Inject constructor(
    @ApplicationContext private val context: Context?,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun file(): File? {
        val dir = context?.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return null
        dir.mkdirs()
        return File(dir, "owrx_favorites.json")
    }

    /** Returns the written file path, or null on failure. */
    fun export(favorites: List<Favorite>): String? = try {
        val f = file() ?: return null
        f.writeText(json.encodeToString(favorites))
        f.absolutePath
    } catch (e: Exception) {
        null
    }

    /** Returns the imported favorites, or null if the file is missing/invalid. */
    fun import(): List<Favorite>? {
        return try {
            val f = file() ?: return null
            if (!f.exists()) return null
            json.decodeFromString<List<Favorite>>(f.readText())
        } catch (e: Exception) {
            null
        }
    }
}
