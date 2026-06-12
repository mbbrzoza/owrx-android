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
    fun wsUrl(): String = when {
        address.startsWith("ws://") || address.startsWith("wss://") -> address
        address.contains(":") -> "ws://$address/ws/"
        else -> "wss://$address/ws/"
    }

    /** Base http(s) URL of the same server. */
    fun httpUrl(): String = when {
        address.startsWith("ws://") -> "http://" + address.removePrefix("ws://").removeSuffix("/ws/").removeSuffix("/")
        address.startsWith("wss://") -> "https://" + address.removePrefix("wss://").removeSuffix("/ws/").removeSuffix("/")
        address.contains(":") -> "http://$address"
        else -> "https://$address"
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
