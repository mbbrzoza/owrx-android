package pl.sp8mb.owrx.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.sp8mb.owrx.data.db.ScanHitDao
import pl.sp8mb.owrx.data.db.ScanHitEntity
import pl.sp8mb.owrx.scanner.ScanMode
import pl.sp8mb.owrx.scanner.ScannerConfig
import pl.sp8mb.owrx.scanner.ScannerEngine
import pl.sp8mb.owrx.session.OwrxSession
import javax.inject.Inject

enum class ScanModeUi { FULL_BAND, RANGE, FAVORITES }

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val engine: ScannerEngine,
    private val scanHitDao: ScanHitDao,
    private val session: OwrxSession,
    private val alerter: pl.sp8mb.owrx.scanner.ScanAlerter,
    prefs: pl.sp8mb.owrx.data.prefs.UserPreferences,
) : ViewModel() {

    val alertEnabled = MutableStateFlow(true)

    fun toggleAlert() {
        alertEnabled.value = !alertEnabled.value
        alerter.enabled = alertEnabled.value
    }

    val status = engine.status
    val profiles = session.profiles
    val connectionState = session.connectionState

    val hits: StateFlow<List<ScanHitEntity>> = scanHitDao.all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<List<pl.sp8mb.owrx.data.prefs.Favorite>> = prefs.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // UI-configurable parameters
    val scanModeUi = MutableStateFlow(ScanModeUi.FULL_BAND)
    val rangeStartMhz = MutableStateFlow("")
    val rangeEndMhz = MutableStateFlow("")
    val thresholdDb = MutableStateFlow(12f)
    val thresholdAuto = MutableStateFlow(true)
    val rasterHz = MutableStateFlow(12500)
    val selectedProfiles = MutableStateFlow<List<String>>(emptyList())

    /** Frequencies of favorites chosen for the memory scan (default: all). */
    val selectedFavorites = MutableStateFlow<Set<Long>>(emptySet())
    private var favoritesInitialized = false

    fun toggleFavorite(freqHz: Long) {
        selectedFavorites.value =
            if (freqHz in selectedFavorites.value) selectedFavorites.value - freqHz
            else selectedFavorites.value + freqHz
    }

    fun selectAllFavorites(all: Boolean) {
        selectedFavorites.value = if (all) favorites.value.map { it.freqHz }.toSet() else emptySet()
    }

    init {
        // pre-select all favorites once they load
        viewModelScope.launch {
            favorites.collect { favs ->
                if (!favoritesInitialized && favs.isNotEmpty()) {
                    favoritesInitialized = true
                    selectedFavorites.value = favs.map { it.freqHz }.toSet()
                }
            }
        }
        viewModelScope.launch {
            engine.hits.collect { hit ->
                val existing = scanHitDao.byFreq(hit.freqHz)
                val now = System.currentTimeMillis()
                scanHitDao.upsert(
                    existing?.copy(lastHeard = now, hitCount = existing.hitCount + 1)
                        ?: ScanHitEntity(
                            freqHz = hit.freqHz,
                            mod = session.desired.value.mod ?: "am",
                            profileId = hit.profileId,
                            firstHeard = now,
                            lastHeard = now,
                        )
                )
            }
        }
        viewModelScope.launch {
            // keep the engine's blacklist in sync with the DB
            hits.collect {
                engine.blacklist = it.filter { h -> h.blacklisted }.map { h -> h.freqHz }.toSet()
            }
        }
        // beep + vibrate on a genuinely new lock (not on HANG->LOCKED re-opens
        // within the same transmission)
        viewModelScope.launch {
            var prev = pl.sp8mb.owrx.scanner.ScanState.IDLE
            engine.status.collect { st ->
                if (st.state == pl.sp8mb.owrx.scanner.ScanState.LOCKED &&
                    prev != pl.sp8mb.owrx.scanner.ScanState.LOCKED &&
                    prev != pl.sp8mb.owrx.scanner.ScanState.HANG
                ) {
                    alerter.lockAlert()
                }
                prev = st.state
            }
        }
    }

    /** Null when the configuration is valid; otherwise a user-facing error. */
    val startError = MutableStateFlow<String?>(null)

    fun start() {
        startError.value = null
        val mode = when (scanModeUi.value) {
            ScanModeUi.FULL_BAND -> ScanMode.FullBand
            ScanModeUi.RANGE -> {
                val start = rangeStartMhz.value.replace(',', '.').toDoubleOrNull()
                val end = rangeEndMhz.value.replace(',', '.').toDoubleOrNull()
                if (start == null || end == null || end <= start) {
                    startError.value = "Podaj poprawny zakres w MHz (od < do)"
                    return
                }
                ScanMode.Range((start * 1e6).toLong(), (end * 1e6).toLong())
            }
            ScanModeUi.FAVORITES -> {
                val targets = favorites.value
                    .filter { it.freqHz in selectedFavorites.value }
                    .map { ScanMode.Favorites.Target(it.freqHz, it.name, it.modulation, it.profileId) }
                if (targets.isEmpty()) {
                    startError.value = "Zaznacz przynajmniej jedną zakładkę"
                    return
                }
                ScanMode.Favorites(targets)
            }
        }
        // favorites scan derives its profile plan from the selected targets' profiles
        val plan = if (scanModeUi.value == ScanModeUi.FAVORITES) {
            favorites.value
                .filter { it.freqHz in selectedFavorites.value }
                .mapNotNull { it.profileId }.distinct()
                .takeIf { it.size > 1 } ?: emptyList()
        } else {
            selectedProfiles.value
        }
        engine.start(
            ScannerConfig(
                thresholdDb = if (thresholdAuto.value) null else thresholdDb.value,
                rasterHz = rasterHz.value,
                mode = mode,
                profilePlan = plan,
            )
        )
    }

    fun stop() = engine.stop()

    fun skip() = engine.skip()

    fun toggleProfile(id: String) {
        selectedProfiles.value =
            if (id in selectedProfiles.value) selectedProfiles.value - id
            else selectedProfiles.value + id
    }

    fun tuneTo(hit: ScanHitEntity) {
        val cfg = session.radioConfig.value
        val center = cfg.centerFreq ?: return
        val half = (cfg.sampRate ?: 0) / 2
        session.setDsp(offsetFreq = (hit.freqHz - center).coerceIn(-half.toLong(), half.toLong()).toInt())
    }

    fun toggleBlacklist(hit: ScanHitEntity) {
        viewModelScope.launch { scanHitDao.setBlacklisted(hit.id, !hit.blacklisted) }
    }

    fun clearHits() {
        viewModelScope.launch { scanHitDao.clearHits() }
    }
}
