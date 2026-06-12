package pl.sp8mb.owrx.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import pl.sp8mb.owrx.protocol.AudioStreamDecoder
import pl.sp8mb.owrx.protocol.ClientCommand
import pl.sp8mb.owrx.protocol.FftFrameDecoder
import pl.sp8mb.owrx.protocol.MessageParser
import pl.sp8mb.owrx.protocol.Profile
import pl.sp8mb.owrx.protocol.ServerMessage
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OwrxSession @Inject constructor(
    private val httpClient: OkHttpClient,
    private val audioPipeline: AudioPipeline,
) {
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data class Connecting(val url: String) : ConnectionState()
        data class Connected(val url: String, val serverVersion: String) : ConnectionState()
        data class Reconnecting(val url: String, val attempt: Int, val delayMs: Long) : ConnectionState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _radioConfig = MutableStateFlow(RadioConfig())
    val radioConfig: StateFlow<RadioConfig> = _radioConfig.asStateFlow()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _smeter = MutableStateFlow(-150f)
    val smeter: StateFlow<Float> = _smeter.asStateFlow()

    private val _backoff = MutableStateFlow<String?>(null)
    val backoff: StateFlow<String?> = _backoff.asStateFlow()

    private val _fft = MutableSharedFlow<FloatArray>(
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val fft: SharedFlow<FloatArray> = _fft.asSharedFlow()

    private val _metadata = MutableSharedFlow<JsonObject>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val metadata: SharedFlow<JsonObject> = _metadata.asSharedFlow()

    private val _log = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val log: SharedFlow<String> = _log.asSharedFlow()

    private val _desired = MutableStateFlow(DesiredState())
    val desired: StateFlow<DesiredState> = _desired.asStateFlow()

    data class ModeInfo(
        val modulation: String,
        val name: String,
        val lowCut: Int?,
        val highCut: Int?,
        val analog: Boolean,
    )

    private val _modes = MutableStateFlow<List<ModeInfo>>(emptyList())
    val modes: StateFlow<List<ModeInfo>> = _modes.asStateFlow()

    /** Learned profile bands: fullProfileId → (centerFreq, sampRate). */
    private val _profileRanges = MutableStateFlow<Map<String, Pair<Long, Int>>>(emptyMap())
    val profileRanges: StateFlow<Map<String, Pair<Long, Int>>> = _profileRanges.asStateFlow()

    private val _bookmarks = MutableStateFlow<kotlinx.serialization.json.JsonElement?>(null)
    val bookmarks: StateFlow<kotlinx.serialization.json.JsonElement?> = _bookmarks.asStateFlow()

    private val _dialFrequencies = MutableStateFlow<kotlinx.serialization.json.JsonElement?>(null)
    val dialFrequencies: StateFlow<kotlinx.serialization.json.JsonElement?> = _dialFrequencies.asStateFlow()

    private val fftDecoder = FftFrameDecoder()
    private val audioDecoder = AudioStreamDecoder()
    private val hdAudioDecoder = AudioStreamDecoder()

    private var webSocket: WebSocket? = null
    private var shouldRun = false
    private var currentUrl: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    @Volatile
    private var handshaken = false

    @Volatile
    private var pendingReplay = false

    /** Wall-clock ms of the last frame from the server; watchdog uses it. */
    private val lastRxAt = AtomicLong(0)

    private var basicAuth: String? = null

    fun connect(url: String, username: String? = null, password: String? = null) {
        shouldRun = true
        currentUrl = url
        basicAuth = if (!username.isNullOrEmpty()) okhttp3.Credentials.basic(username, password ?: "") else null
        reconnectAttempt = 0
        _backoff.value = null
        _radioConfig.value = RadioConfig()
        openSocket(url)
        startWatchdog()
    }

    fun disconnect() {
        shouldRun = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "bye")
        webSocket = null
        audioPipeline.stop()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun selectProfile(profileId: String) {
        _desired.value = _desired.value.copy(profileId = profileId)
        send(ClientCommand.selectProfile(profileId))
    }

    fun setDsp(
        offsetFreq: Int? = null,
        mod: String? = null,
        squelchLevel: Float? = null,
        lowCut: Int? = null,
        highCut: Int? = null,
    ) {
        val d = _desired.value
        _desired.value = d.copy(
            offsetFreq = offsetFreq ?: d.offsetFreq,
            mod = mod ?: d.mod,
            squelchLevel = squelchLevel ?: d.squelchLevel,
            lowCut = lowCut ?: d.lowCut,
            highCut = highCut ?: d.highCut,
        )
        val params = buildMap<String, Any?> {
            offsetFreq?.let { put("offset_freq", it) }
            mod?.let { put("mod", it) }
            squelchLevel?.let { put("squelch_level", it) }
            lowCut?.let { put("low_cut", it) }
            highCut?.let { put("high_cut", it) }
        }
        if (params.isNotEmpty()) send(ClientCommand.dspParams(params))
    }

    fun setNr(enabled: Boolean, threshold: Int) {
        _desired.value = _desired.value.copy(nrEnabled = enabled, nrThreshold = threshold)
        send(ClientCommand.dspParams(mapOf("nr_enabled" to enabled, "nr_threshold" to threshold)))
    }

    /** Kicks the reconnect loop immediately (e.g. on network-available callback). */
    fun kickReconnect() {
        if (shouldRun && _connectionState.value !is ConnectionState.Connected) {
            reconnectJob?.cancel()
            currentUrl?.let { openSocket(it) }
        }
    }

    private fun openSocket(url: String) {
        // kill any previous socket first — two live sockets would feed the
        // shared decoders from two reader threads (observed AIOOBE race)
        webSocket?.cancel()
        handshaken = false
        _connectionState.value = ConnectionState.Connecting(url)
        val request = Request.Builder().url(url).apply {
            basicAuth?.let { header("Authorization", it) }
        }.build()
        webSocket = httpClient.newWebSocket(request, listener)
    }

    /** True if this callback comes from the current socket; stale sockets are ignored. */
    private fun isCurrent(ws: WebSocket) = ws === webSocket

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            lastRxAt.set(System.currentTimeMillis())
            webSocket.send(ClientCommand.HANDSHAKE)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            lastRxAt.set(System.currentTimeMillis())
            if (!handshaken) {
                if (text.startsWith("CLIENT DE SERVER")) {
                    handshaken = true
                    pendingReplay = true
                    reconnectAttempt = 0
                    val version = text.substringAfter("version=", "?")
                    _connectionState.value = ConnectionState.Connected(currentUrl ?: "", version)
                    webSocket.send(ClientCommand.connectionProperties())
                    webSocket.send(ClientCommand.dspStart())
                    audioPipeline.start()
                }
                return
            }
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(webSocket)) return
            lastRxAt.set(System.currentTimeMillis())
            if (bytes.size < 1) return
            val payload = bytes.toByteArray()
            try {
                when (payload[0].toInt()) {
                    1 -> _fft.tryEmit(fftDecoder.decode(payload.copyOfRange(1, payload.size)))
                    2 -> audioPipeline.submit(audioDecoder.decode(payload.copyOfRange(1, payload.size)), hd = false)
                    3 -> {} // secondary FFT — not used yet
                    4 -> audioPipeline.submit(hdAudioDecoder.decode(payload.copyOfRange(1, payload.size)), hd = true)
                }
            } catch (e: Exception) {
                // a decoder hiccup must not kill the connection
                android.util.Log.w("OwrxSession", "binary frame decode error", e)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket)) return
            scheduleReconnect("closed: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket)) return
            android.util.Log.w("OwrxSession", "ws failure", t)
            scheduleReconnect("failure: ${t.message}")
        }
    }

    private fun handleMessage(text: String) {
        when (val msg = MessageParser.parse(text)) {
            is ServerMessage.Config -> {
                val merged = _radioConfig.value.merge(msg.value)
                _radioConfig.value = merged
                fftDecoder.compression = merged.fftCompression
                audioDecoder.compression = merged.audioCompression
                hdAudioDecoder.compression = merged.audioCompression
                val pid = merged.fullProfileId
                if (pid != null && merged.centerFreq != null && merged.sampRate != null) {
                    _profileRanges.value = _profileRanges.value + (pid to (merged.centerFreq to merged.sampRate))
                }
                if (pendingReplay) {
                    pendingReplay = false
                    replayDesiredState()
                }
            }
            is ServerMessage.Profiles -> _profiles.value = msg.profiles
            is ServerMessage.SMeter -> _smeter.value = msg.level
            is ServerMessage.Metadata -> _metadata.tryEmit(msg.value)
            is ServerMessage.Backoff -> {
                _backoff.value = msg.reason.ifEmpty { "server backoff" }
                _log.tryEmit("BACKOFF: ${msg.reason}")
            }
            is ServerMessage.LogMessage -> _log.tryEmit(msg.message)
            is ServerMessage.SdrError -> _log.tryEmit("SDR error: ${msg.message}")
            is ServerMessage.DemodulatorError -> _log.tryEmit("Demod error: ${msg.message}")
            is ServerMessage.Bookmarks -> _bookmarks.value = msg.value
            is ServerMessage.DialFrequencies -> _dialFrequencies.value = msg.value
            is ServerMessage.Modes -> _modes.value = parseModes(msg.value)
            else -> {}
        }
    }

    private fun parseModes(value: kotlinx.serialization.json.JsonElement): List<ModeInfo> = try {
        (value as kotlinx.serialization.json.JsonArray).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun prim(key: String) = o[key] as? kotlinx.serialization.json.JsonPrimitive
            val bandpass = o["bandpass"] as? JsonObject
            ModeInfo(
                modulation = prim("modulation")?.content ?: return@mapNotNull null,
                name = prim("name")?.content ?: "",
                lowCut = (bandpass?.get("low_cut") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()?.toInt(),
                highCut = (bandpass?.get("high_cut") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()?.toInt(),
                analog = prim("type")?.content == "analog",
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Restore persisted user state (called before connect). */
    fun restoreDesiredState(state: DesiredState) {
        _desired.value = state
    }

    private fun replayDesiredState() {
        val d = _desired.value
        val params = d.dspParams()
        if (params.isNotEmpty()) send(ClientCommand.dspParams(params))
        // profile replay deliberately omitted: the server restores the last
        // profile by itself and forcing it would fight other listeners
    }

    private fun send(text: String) {
        webSocket?.send(text)
    }

    private fun scheduleReconnect(reason: String) {
        android.util.Log.w("OwrxSession", "connection lost: $reason")
        audioDecoder.reset()
        hdAudioDecoder.reset()
        if (!shouldRun) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }
        val url = currentUrl ?: return
        reconnectAttempt++
        val delayMs = (1000L shl (reconnectAttempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
        _connectionState.value = ConnectionState.Reconnecting(url, reconnectAttempt, delayMs)
        _log.tryEmit("Reconnect #$reconnectAttempt in ${delayMs}ms ($reason)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (shouldRun) openSocket(url)
        }
    }

    private var watchdogJob: Job? = null

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (shouldRun) {
                delay(10_000)
                val silentMs = System.currentTimeMillis() - lastRxAt.get()
                if (_connectionState.value is ConnectionState.Connected && silentMs > 30_000) {
                    _log.tryEmit("Watchdog: ${silentMs / 1000}s silence, forcing reconnect")
                    webSocket?.cancel()
                }
            }
        }
    }
}
