package pl.sp8mb.owrx.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import pl.sp8mb.owrx.data.prefs.UserPreferences
import pl.sp8mb.owrx.session.OwrxSession
import javax.inject.Inject

data class BookmarkItem(
    val name: String,
    val frequency: Long,
    val modulation: String?,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class ReceiverViewModel @Inject constructor(
    val session: OwrxSession,
    private val prefs: UserPreferences,
    private val audioPipeline: pl.sp8mb.owrx.session.AudioPipeline,
    private val recorder: pl.sp8mb.owrx.session.AudioRecorder,
    private val serverDao: pl.sp8mb.owrx.data.db.ServerDao,
    dvoice: pl.sp8mb.owrx.dvoice.DigitalVoiceRepository,
) : ViewModel() {

    val digitalVoice = dvoice.active
    val clientCount = session.clientCount
    val chat = session.chat

    fun sendChat(text: String) = session.sendChat(text, "owrx-android")

    val muted = audioPipeline.userMuted
    val volume = audioPipeline.volume

    fun toggleMute() {
        audioPipeline.userMuted.value = !audioPipeline.userMuted.value
    }

    fun setVolume(v: Float) {
        audioPipeline.volume.value = v
    }

    // ── recording ──

    val recorderState = recorder.state
    val voxEnabled = recorder.voxEnabled

    init {
        // VOX file names follow the live frequency
        recorder.labelProvider = { "%.4fMHz".format(tunedFreq.value / 1e6).replace(',', '.') }
    }

    fun toggleRecording() {
        if (recorder.state.value.recording) {
            recorder.stop()
        } else {
            val rate = pl.sp8mb.owrx.session.AudioPipeline.BASE_RATE
            recorder.start(rate, "%.4fMHz".format(tunedFreq.value / 1e6).replace(',', '.'))
        }
    }

    /** VOX: auto-record each transmission (starts when squelch opens). */
    fun toggleVox() {
        recorder.setVox(!recorder.voxEnabled.value)
    }

    // ── device gain via the admin panel (rf_gain-select/-manual on the DEVICE form) ──

    data class GainState(val auto: Boolean, val manual: Float)

    /** Current device gain, or null when admin access is unavailable. */
    val gainState = kotlinx.coroutines.flow.MutableStateFlow<GainState?>(null)

    private var adminClient: pl.sp8mb.owrx.admin.AdminClient? = null
    private var gainForm: pl.sp8mb.owrx.admin.AdminClient.FormPage? = null
    private var gainPath: String? = null
    private var gainSubmitJob: kotlinx.coroutines.Job? = null

    private suspend fun ensureAdmin(): pl.sp8mb.owrx.admin.AdminClient? {
        adminClient?.let { return it }
        val serverId = prefs.lastServerId.first() ?: run {
            android.util.Log.w(TAG, "gain: no last server id")
            return null
        }
        val server = serverDao.byId(serverId) ?: run {
            android.util.Log.w(TAG, "gain: server $serverId not in db")
            return null
        }
        if (server.adminUser.isNullOrEmpty()) {
            android.util.Log.w(TAG, "gain: no admin credentials for '${server.name}'")
            return null
        }
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                pl.sp8mb.owrx.admin.AdminClient(
                    baseUrl = server.httpUrl(),
                    username = server.adminUser,
                    password = server.adminPassword ?: "",
                    basicAuthUser = server.username,
                    basicAuthPassword = server.password,
                ).also { it.login() }
            }.also {
                adminClient = it
                android.util.Log.i(TAG, "gain: admin login OK on ${server.httpUrl()}")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "gain: admin login FAILED", e)
            null
        }
    }

    private suspend fun loadGain(sdrId: String) {
        val client = ensureAdmin() ?: run { gainState.value = null; return }
        try {
            // device gain lives on the DEVICE form, not the profile form
            val path = "/settings/sdr/$sdrId"
            val form = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.loadForm(path)
            }
            val select = form.fields.firstOrNull { it.name == "rf_gain-select" }
            val manual = form.fields.firstOrNull { it.name == "rf_gain-manual" }
            gainPath = path
            gainForm = form
            android.util.Log.i(
                TAG,
                "gain: form $path fields=" + form.fields.joinToString { it.name } +
                    " select=${select?.value} manual=${manual?.value}",
            )
            gainState.value = if (select != null) {
                GainState(
                    auto = select.value == "auto",
                    manual = manual?.value?.toFloatOrNull() ?: 0f,
                )
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "gain: loadGain failed", e)
            gainState.value = null
        }
    }

    private companion object {
        const val TAG = "ReceiverVM"
    }

    private fun submitGain(auto: Boolean, manual: Float) {
        val form = gainForm ?: return
        gainState.value = GainState(auto, manual)
        gainForm = form.copy(
            fields = form.fields.map {
                when (it.name) {
                    "rf_gain-select" -> it.copy(value = if (auto) "auto" else "manual")
                    "rf_gain-manual" -> it.copy(value = "%.1f".format(manual).replace(',', '.'))
                    else -> it
                }
            }
        )
        gainSubmitJob?.cancel()
        gainSubmitJob = viewModelScope.launch {
            kotlinx.coroutines.delay(700)
            val client = adminClient ?: return@launch
            val f = gainForm ?: return@launch
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.submitForm(f.action, f.fields)
                }
            } catch (e: Exception) {
                android.util.Log.w("ReceiverVM", "gain submit", e)
            }
        }
    }

    fun setGainAuto(auto: Boolean) {
        val g = gainState.value ?: return
        submitGain(auto, g.manual)
    }

    fun adjustGain(delta: Float) {
        val g = gainState.value ?: return
        submitGain(auto = false, manual = (g.manual + delta).coerceIn(0f, 60f))
    }

    /** Web-style auto squelch: current S-meter + configured margin. */
    fun autoSquelch() {
        val margin = session.radioConfig.value.raw["squelch_auto_margin"]?.toFloatOrNull() ?: 10f
        setSquelch((session.smeter.value + margin).coerceIn(-150f, 0f))
    }

    fun setNr(enabled: Boolean, threshold: Int) = session.setNr(enabled, threshold)

    val connectionState = session.connectionState
    val radioConfig = session.radioConfig
    val profiles = session.profiles
    val smeter = session.smeter
    val backoff = session.backoff
    val modes = session.modes
    val desired = session.desired
    val fft = session.fft

    /** Absolute tuned frequency in Hz. */
    val tunedFreq: StateFlow<Long> = combine(session.radioConfig, session.desired) { cfg, des ->
        val center = cfg.centerFreq ?: return@combine 0L
        center + (des.offsetFreq ?: cfg.startOffsetFreq ?: 0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val currentMod: StateFlow<String> = combine(session.radioConfig, session.desired) { cfg, des ->
        des.mod ?: cfg.startMod ?: "nfm"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "nfm")

    val bookmarks: StateFlow<List<BookmarkItem>> =
        combine(session.bookmarks, session.dialFrequencies) { bm, dial ->
            parseBookmarks(bm) + parseBookmarks(dial)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<List<pl.sp8mb.owrx.data.prefs.Favorite>> = prefs.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Favorite waiting for a profile switch before tuning. */
    private var pendingFavorite: pl.sp8mb.owrx.data.prefs.Favorite? = null

    init {
        // persist user tuning (debounced) so a restart resumes where we were
        viewModelScope.launch {
            session.desired.drop(1).debounce(2000).collect { prefs.saveDesiredState(it) }
        }
        // learn favorites: server sends bookmarks for the current band only,
        // so accumulate them per profile as the user visits bands
        viewModelScope.launch {
            combine(bookmarks, session.radioConfig) { bm, cfg -> bm to cfg.fullProfileId }
                .debounce(1000)
                .collect { (bm, profileId) ->
                    if (profileId == null || bm.isEmpty()) return@collect
                    val current = favorites.value
                    val merged = (current + bm.map {
                        pl.sp8mb.owrx.data.prefs.Favorite(it.name, it.frequency, it.modulation, profileId)
                    }).distinctBy { it.freqHz to it.name }
                    if (merged.size != current.size) prefs.saveFavorites(merged)
                }
        }
        // refresh device gain whenever the active SDR changes (admin access required)
        viewModelScope.launch {
            session.radioConfig
                .map { it.sdrId }
                .distinctUntilChanged()
                .collect { sdr ->
                    gainForm = null
                    if (sdr != null) loadGain(sdr) else gainState.value = null
                }
        }
        // complete a cross-band favorite jump once the new profile's config arrives
        viewModelScope.launch {
            session.radioConfig.collect { cfg ->
                val fav = pendingFavorite ?: return@collect
                val center = cfg.centerFreq ?: return@collect
                val half = (cfg.sampRate ?: return@collect) / 2
                if (fav.freqHz in (center - half)..(center + half)) {
                    pendingFavorite = null
                    tune(fav.freqHz)
                    fav.modulation?.let { setMode(it) }
                }
            }
        }
    }

    /** Save the currently tuned frequency as a favorite. */
    fun addFavorite(name: String) {
        val freq = tunedFreq.value
        if (freq <= 0) return
        val fav = pl.sp8mb.owrx.data.prefs.Favorite(
            name = name.ifBlank { "%.4f MHz".format(freq / 1e6) },
            freqHz = freq,
            modulation = currentMod.value,
            profileId = session.radioConfig.value.fullProfileId,
        )
        viewModelScope.launch {
            prefs.saveFavorites(
                (favorites.value.filterNot { it.freqHz == fav.freqHz && it.name == fav.name } + fav)
                    .sortedBy { it.freqHz }
            )
        }
    }

    fun removeFavorite(fav: pl.sp8mb.owrx.data.prefs.Favorite) {
        viewModelScope.launch {
            prefs.saveFavorites(favorites.value - fav)
        }
    }

    /**
     * Tune to any frequency: inside the current band directly, otherwise jump
     * to a learned profile whose band covers it (then tune after the config).
     */
    fun tuneAnywhere(freqHz: Long): Boolean {
        val cfg = session.radioConfig.value
        val center = cfg.centerFreq
        val half = (cfg.sampRate ?: 0) / 2
        if (center != null && freqHz in (center - half)..(center + half)) {
            tune(freqHz)
            return true
        }
        val profile = session.profileRanges.value.entries.firstOrNull { (_, range) ->
            val (c, samp) = range
            freqHz in (c - samp / 2)..(c + samp / 2)
        }?.key ?: return false
        pendingFavorite = pl.sp8mb.owrx.data.prefs.Favorite("", freqHz, null, profile)
        session.selectProfile(profile)
        return true
    }

    /** Tune to a favorite — switching profile first when it lives in another band. */
    fun tuneToFavorite(fav: pl.sp8mb.owrx.data.prefs.Favorite) {
        val cfg = session.radioConfig.value
        val center = cfg.centerFreq
        val half = (cfg.sampRate ?: 0) / 2
        if (center != null && fav.freqHz in (center - half)..(center + half)) {
            tune(fav.freqHz)
            fav.modulation?.let { setMode(it) }
        } else if (fav.profileId != null) {
            pendingFavorite = fav
            session.selectProfile(fav.profileId)
        }
    }

    fun tune(freqHz: Long) {
        val cfg = session.radioConfig.value
        val center = cfg.centerFreq ?: return
        val half = (cfg.sampRate ?: 0) / 2
        val offset = (freqHz - center).coerceIn(-half.toLong(), half.toLong()).toInt()
        session.setDsp(offsetFreq = offset)
    }

    /** Step by stepHz (signed) and snap the result to the |stepHz| raster. */
    fun tuneStep(stepHz: Int) {
        val freq = tunedFreq.value
        if (freq <= 0L || stepHz == 0) return
        val raster = kotlin.math.abs(stepHz).toLong()
        val target = freq + stepHz
        val snapped = ((target + raster / 2) / raster) * raster
        tune(snapped)
    }

    fun setMode(modulation: String) {
        val info = session.modes.value.firstOrNull { it.modulation == modulation }
        session.setDsp(mod = modulation, lowCut = info?.lowCut, highCut = info?.highCut)
    }

    fun setSquelch(levelDb: Float) {
        session.setDsp(squelchLevel = levelDb)
    }

    fun selectProfile(id: String) = session.selectProfile(id)

    private fun parseBookmarks(el: kotlinx.serialization.json.JsonElement?): List<BookmarkItem> {
        val arr = (el as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            fun s(key: String) = (o[key] as? JsonPrimitive)?.content
            val freq = s("frequency")?.toLongOrNull() ?: return@mapNotNull null
            BookmarkItem(
                name = s("name") ?: s("mode") ?: "$freq",
                frequency = freq,
                modulation = s("modulation") ?: s("underlying"),
            )
        }
    }
}
