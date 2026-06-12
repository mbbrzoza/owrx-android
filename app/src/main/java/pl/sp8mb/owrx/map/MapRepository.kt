package pl.sp8mb.owrx.map

import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import pl.sp8mb.owrx.data.db.ServerDao
import pl.sp8mb.owrx.data.prefs.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Separate WebSocket (handshake type=map) that streams position reports
 * (APRS/AIS/HFDL/...) for the map view. Positions expire after a TTL.
 */
@Singleton
class MapRepository @Inject constructor(
    private val httpClient: OkHttpClient,
    private val serverDao: ServerDao,
    private val prefs: UserPreferences,
    @ApplicationContext private val context: android.content.Context?,
) {
    data class Position(
        val callsign: String,
        val lat: Double,
        val lon: Double,
        val mode: String?,
        val lastSeen: Long,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _positions = MutableStateFlow<Map<String, Position>>(emptyMap())
    val positions: StateFlow<Map<String, Position>> = _positions.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var webSocket: WebSocket? = null
    private var handshaken = false

    fun connect() {
        scope.launch {
            val serverId = prefs.lastServerId.first() ?: return@launch
            val server = serverDao.byId(serverId) ?: return@launch
            val req = Request.Builder().url(server.wsUrl()).apply {
                if (!server.username.isNullOrEmpty()) {
                    header("Authorization", okhttp3.Credentials.basic(server.username, server.password ?: ""))
                }
            }.build()
            handshaken = false
            webSocket?.cancel()
            webSocket = httpClient.newWebSocket(req, listener)
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
        _connected.value = false
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("SERVER DE CLIENT client=owrx-android type=map")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!handshaken) {
                if (text.startsWith("CLIENT DE SERVER")) {
                    handshaken = true
                    _connected.value = true
                }
                return
            }
            handle(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connected.value = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
        }
    }

    private fun handle(text: String) {
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return
        if ((obj["type"] as? JsonPrimitive)?.content != "update") return
        val records = obj["value"] as? JsonArray ?: return
        val now = System.currentTimeMillis()
        val updated = _positions.value.toMutableMap()
        for (rec in records) {
            val r = rec as? JsonObject ?: continue
            val callsign = (r["callsign"] as? JsonPrimitive)?.content ?: continue
            val loc = r["location"] as? JsonObject
            val lat = (loc?.get("lat") as? JsonPrimitive)?.content?.toDoubleOrNull()
            val lon = (loc?.get("lon") as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (lat == null || lon == null) continue
            updated[callsign] = Position(
                callsign = callsign,
                lat = lat,
                lon = lon,
                mode = (r["mode"] as? JsonPrimitive)?.content,
                lastSeen = now,
            )
        }
        // expire stale
        updated.entries.removeAll { now - it.value.lastSeen > TTL_MS }
        _positions.value = updated
    }

    companion object {
        const val TTL_MS = 30 * 60 * 1000L
    }
}
