package pl.sp8mb.owrx.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** host, host:port or full ws[s]:// URL */
    val address: String,
    val username: String? = null,
    val password: String? = null,
    /** OpenWebRX admin panel credentials (for SDR/profile management) */
    val adminUser: String? = null,
    val adminPassword: String? = null,
    /** magic key for center-frequency changes (server's magic_key setting) */
    val magicKey: String? = null,
) {
    fun wsUrl(): String {
        val a = address.trim()
        return when {
            a.startsWith("ws://") || a.startsWith("wss://") -> a
            a.startsWith("https://") -> "wss://" + a.removePrefix("https://").removeSuffix("/") + "/ws/"
            a.startsWith("http://") -> "ws://" + a.removePrefix("http://").removeSuffix("/") + "/ws/"
            a.endsWith(":443") -> "wss://$a/ws/"            // jawny port HTTPS -> TLS
            a.contains(":") -> "ws://$a/ws/"                // host:port (lokalny OWRX) -> cleartext
            else -> "wss://$a/ws/"                          // sama domena -> TLS (np. sdr.inter1.pl)
        }
    }

    /** Base http(s) URL of the same server. */
    fun httpUrl(): String {
        val a = address.trim()
        return when {
            a.startsWith("ws://") -> "http://" + a.removePrefix("ws://").removeSuffix("/ws/").removeSuffix("/")
            a.startsWith("wss://") -> "https://" + a.removePrefix("wss://").removeSuffix("/ws/").removeSuffix("/")
            a.startsWith("http://") || a.startsWith("https://") -> a.removeSuffix("/")
            a.endsWith(":443") -> "https://$a"
            a.contains(":") -> "http://$a"
            else -> "https://$a"
        }
    }
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name")
    fun all(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun byId(id: Long): ServerEntity?

    @Upsert
    suspend fun upsert(server: ServerEntity): Long

    @Delete
    suspend fun delete(server: ServerEntity)
}
