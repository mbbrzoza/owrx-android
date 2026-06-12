package pl.sp8mb.owrx.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
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
) : ViewModel() {

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

    init {
        // persist user tuning (debounced) so a restart resumes where we were
        viewModelScope.launch {
            session.desired.drop(1).debounce(2000).collect { prefs.saveDesiredState(it) }
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
