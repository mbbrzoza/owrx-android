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
