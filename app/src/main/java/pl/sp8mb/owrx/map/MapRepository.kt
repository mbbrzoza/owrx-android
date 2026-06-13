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
    session: pl.sp8mb.owrx.session.OwrxSession,
) {
    data class Position(
        val callsign: String,
        val lat: Double,
        val lon: Double,
        val mode: String?,
        val comment: String?,
        val isLocator: Boolean,
        val lastSeen: Long,
        val local: Boolean = false,   // zdekodowane lokalnie (secondary_demod) — wyróżniane na mapie
    )

    data class ReceiverInfo(val lat: Double, val lon: Double, val name: String?, val location: String?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _positions = MutableStateFlow<Map<String, Position>>(emptyMap())
    val positions: StateFlow<Map<String, Position>> = _positions.asStateFlow()

    init {
        // Lokalnie zdekodowane ramki (APRS/AIS/... z secondary_demod) → na mapę jako 'local'.
        scope.launch {
            session.decodedPositions.collect { dp ->
                mergePositions(listOf(Position(
                    callsign = dp.callsign, lat = dp.lat, lon = dp.lon,
                    mode = dp.mode ?: "DECODED", comment = dp.comment,
                    isLocator = false, lastSeen = System.currentTimeMillis(), local = true,
                )))
            }
        }
    }

    /** Scal nowe pozycje z istniejącymi (synchronizowane — dwa źródła: mapa-WS i lokalne dekody). */
    @Synchronized
    private fun mergePositions(newOnes: List<Position>) {
        if (newOnes.isEmpty()) return
        val now = System.currentTimeMillis()
        val updated = _positions.value.toMutableMap()
        for (p in newOnes) {
            // nie nadpisuj świeżej pozycji 'local' danymi z mapy-WS (zachowaj wyróżnienie)
            val prev = updated[p.callsign]
            updated[p.callsign] = if (!p.local && prev?.local == true && now - prev.lastSeen < 120_000) {
                prev.copy(lat = p.lat, lon = p.lon, lastSeen = p.lastSeen)
            } else p
        }
        updated.entries.removeAll { now - it.value.lastSeen > TTL_MS }
        _positions.value = updated
    }

    private val _receiver = MutableStateFlow<ReceiverInfo?>(null)
    val receiver: StateFlow<ReceiverInfo?> = _receiver.asStateFlow()

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
        val type = (obj["type"] as? JsonPrimitive)?.content
        if (type == "receiver_details") {
            val v = obj["value"] as? JsonObject
            val gps = v?.get("receiver_gps") as? JsonObject
            val lat = (gps?.get("lat") as? JsonPrimitive)?.content?.toDoubleOrNull()
            val lon = (gps?.get("lon") as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (lat != null && lon != null) {
                _receiver.value = ReceiverInfo(
                    lat, lon,
                    (v["receiver_name"] as? JsonPrimitive)?.content,
                    (v["receiver_location"] as? JsonPrimitive)?.content,
                )
            }
            return
        }
        if (type != "update") return
        val records = obj["value"] as? JsonArray ?: return
        val now = System.currentTimeMillis()
        val newOnes = ArrayList<Position>()
        for (rec in records) {
            val r = rec as? JsonObject ?: continue
            val callsign = (r["callsign"] as? JsonPrimitive)?.content ?: continue
            val loc = r["location"] as? JsonObject
            val lat = (loc?.get("lat") as? JsonPrimitive)?.content?.toDoubleOrNull()
            val lon = (loc?.get("lon") as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (lat == null || lon == null) continue
            newOnes.add(Position(
                callsign = callsign,
                lat = lat,
                lon = lon,
                mode = (r["mode"] as? JsonPrimitive)?.content
                    ?: (loc?.get("mode") as? JsonPrimitive)?.content,
                comment = (loc?.get("comment") as? JsonPrimitive)?.content,
                isLocator = (loc?.get("type") as? JsonPrimitive)?.content == "locator",
                lastSeen = now,
            ))
        }
        mergePositions(newOnes)
    }

    companion object {
        const val TTL_MS = 60 * 60 * 1000L
    }
}
