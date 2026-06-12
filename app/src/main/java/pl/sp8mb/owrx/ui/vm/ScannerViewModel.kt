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
import pl.sp8mb.owrx.scanner.ScannerConfig
import pl.sp8mb.owrx.scanner.ScannerEngine
import pl.sp8mb.owrx.session.OwrxSession
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val engine: ScannerEngine,
    private val scanHitDao: ScanHitDao,
    private val session: OwrxSession,
) : ViewModel() {

    val status = engine.status
    val profiles = session.profiles
    val connectionState = session.connectionState

    val hits: StateFlow<List<ScanHitEntity>> = scanHitDao.all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // UI-configurable parameters
    val thresholdDb = MutableStateFlow(12f)
    val rasterHz = MutableStateFlow(12500)
    val selectedProfiles = MutableStateFlow<List<String>>(emptyList())

    init {
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
    }

    fun start() {
        engine.start(
            ScannerConfig(
                thresholdDb = thresholdDb.value,
                rasterHz = rasterHz.value,
                profilePlan = selectedProfiles.value,
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
