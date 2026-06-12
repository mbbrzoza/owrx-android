package pl.sp8mb.owrx.scanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ScanMode {
    /** scan everything visible in the current profile band */
    data object FullBand : ScanMode()

    /** scan only inside [startHz, endHz] (intersected with the visible band) */
    data class Range(val startHz: Long, val endHz: Long) : ScanMode()

    /** memory scan: watch only the given frequencies (favorites) */
    data class Favorites(val targets: List<Target>) : ScanMode() {
        data class Target(val freqHz: Long, val name: String, val modulation: String?, val profileId: String?)
    }
}

data class ScannerConfig(
    val thresholdDb: Float = 12f,
    val rasterHz: Int = 12500,
    val mode: ScanMode = ScanMode.FullBand,
    /** carrier must persist this long before we call it a hit */
    val confirmMs: Long = 400,
    /** keep listening this long after the carrier drops */
    val hangMs: Long = 3000,
    /** don't immediately re-lock the same frequency */
    val cooldownMs: Long = 8000,
    /** profile ids to cycle through; empty = stay on current profile */
    val profilePlan: List<String> = emptyList(),
    /** dwell per profile; the 10 s floor protects against the server bot-ban */
    val dwellMs: Long = 12_000,
) {
    val safeDwellMs: Long get() = dwellMs.coerceAtLeast(MIN_DWELL_MS)

    companion object {
        /** Server bans clients hopping profiles too fast (robotScore >= 30 → 12 h ban). */
        const val MIN_DWELL_MS = 10_000L
    }
}

enum class ScanState { IDLE, SCANNING, SETTLING, LOCKED, HANG }

data class ScannerStatus(
    val state: ScanState = ScanState.IDLE,
    val lockedFreq: Long? = null,
    val lockedDb: Float? = null,
    val profileIndex: Int = 0,
    val message: String = "",
)

data class ScanHit(val freqHz: Long, val peakDb: Float, val profileId: String?)

/**
 * Scan state machine fed by FFT frames.
 *
 * SCANNING: look for carriers above floor+threshold; confirmed peak → tune + LOCKED.
 *           In multi-profile plans, hop to the next profile after dwell expires.
 * SETTLING: profile switch sent; wait until center_freq changes (or timeout).
 * LOCKED:   stay while the carrier is present at the locked frequency.
 * HANG:     carrier dropped; resume scanning after hangMs unless it returns.
 */
class ScannerEngine(
    private val host: ScannerHost,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(ScannerStatus())
    val status: StateFlow<ScannerStatus> = _status.asStateFlow()

    private val _hits = MutableSharedFlow<ScanHit>(extraBufferCapacity = 64)
    val hits: SharedFlow<ScanHit> = _hits.asSharedFlow()

    @Volatile
    var blacklist: Set<Long> = emptySet()

    private var config = ScannerConfig()
    private var job: Job? = null

    // mutable run state
    private var state = ScanState.IDLE
    private var lockedFreq = 0L
    private var lockedSince = 0L
    private var hangStart = 0L
    private var candidateFreq = 0L
    private var candidateSince = 0L
    private var lastSeenLocked = 0L
    private var profileIndex = 0
    private var profileEnteredAt = 0L
    private var settleStarted = 0L
    private var settleOldCenter = 0L
    private val recentVisits = HashMap<Long, Long>()

    fun start(cfg: ScannerConfig) {
        stop()
        config = cfg
        state = ScanState.SCANNING
        profileIndex = 0
        profileEnteredAt = host.now()
        recentVisits.clear()
        publish("Skanowanie…")
        job = scope.launch {
            host.fft.collect { frame -> onFrame(frame) }
        }
        // if a multi-profile plan is set, start from its first profile
        if (cfg.profilePlan.size > 1) {
            switchToProfile(0)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        state = ScanState.IDLE
        publish("Zatrzymany")
    }

    /** User skip: blacklist current lock for the cooldown period and resume. */
    fun skip() {
        if (state == ScanState.LOCKED || state == ScanState.HANG) {
            recentVisits[lockedFreq] = host.now()
            resumeScan("Pominięto %.4f MHz".format(lockedFreq / 1e6))
        }
    }

    private fun onFrame(frame: FloatArray) {
        if (host.backoff.value != null) {
            stop()
            publish("STOP: serwer zgłosił backoff (ochrona przed banem)")
            return
        }
        val cfg = host.radioConfig.value
        val center = cfg.centerFreq ?: return
        val samp = cfg.sampRate ?: return
        val now = host.now()

        when (state) {
            ScanState.IDLE -> {}

            ScanState.SETTLING -> {
                // wait for the new profile's config to arrive
                if (center != settleOldCenter || now - settleStarted > SETTLE_TIMEOUT_MS) {
                    state = ScanState.SCANNING
                    profileEnteredAt = now
                    publish("Skanowanie (profil ${profileIndex + 1}/${config.profilePlan.size})…")
                }
            }

            ScanState.SCANNING -> {
                val peak = findCandidate(frame, center, samp, now)
                if (peak != null) {
                    if (candidateFreq != peak.freq) {
                        candidateFreq = peak.freq
                        candidateSince = now
                    }
                    if (now - candidateSince >= config.confirmMs) {
                        lock(peak, center, samp, now)
                    }
                } else {
                    candidateFreq = 0
                    maybeHopProfile(now)
                }
            }

            ScanState.LOCKED -> {
                if (carrierPresent(frame, center, samp)) {
                    lastSeenLocked = now
                } else if (now - lastSeenLocked > CARRIER_DROP_MS) {
                    state = ScanState.HANG
                    hangStart = now
                    publish("Zanik — czekam %.0f s".format(config.hangMs / 1000.0))
                }
            }

            ScanState.HANG -> {
                if (carrierPresent(frame, center, samp)) {
                    state = ScanState.LOCKED
                    lastSeenLocked = now
                    publish("Nasłuch %.4f MHz".format(lockedFreq / 1e6))
                } else if (now - hangStart >= config.hangMs) {
                    recentVisits[lockedFreq] = now
                    resumeScan("Wznawiam skanowanie")
                }
            }
        }
    }

    data class Candidate(val freq: Long, val peakDb: Float, val floorDb: Float, val modulation: String? = null, val name: String? = null)

    private fun findCandidate(frame: FloatArray, center: Long, samp: Int, now: Long): Candidate? {
        val mode = config.mode
        if (mode is ScanMode.Favorites) return findFavoriteCandidate(mode, frame, center, samp, now)

        val peaks = CarrierDetector.detect(frame, config.thresholdDb)
        var best: Candidate? = null
        for (p in peaks) {
            val freq = CarrierDetector.snapToRaster(
                CarrierDetector.binToFreq(p.peakBin, frame.size, center, samp),
                config.rasterHz,
            )
            if (mode is ScanMode.Range && (freq < mode.startHz || freq > mode.endHz)) continue
            if (freq in blacklist) continue
            val lastVisit = recentVisits[freq]
            if (lastVisit != null && now - lastVisit < config.cooldownMs) continue
            // skip band edges (filter rolloff causes false peaks)
            val edge = (samp * EDGE_MARGIN).toLong()
            if (freq < center - samp / 2 + edge || freq > center + samp / 2 - edge) continue
            if (best == null || p.peakDb > best.peakDb) best = Candidate(freq, p.peakDb, p.floorDb)
        }
        return best
    }

    /** Memory scan: check only the favorite frequencies that fall in the current band. */
    private fun findFavoriteCandidate(
        mode: ScanMode.Favorites,
        frame: FloatArray,
        center: Long,
        samp: Int,
        now: Long,
    ): Candidate? {
        val floor = CarrierDetector.noiseFloor(frame)
        val halfWin = (frame.size * config.rasterHz.toFloat() / samp / 2).toInt().coerceAtLeast(2)
        var best: Candidate? = null
        for (t in mode.targets) {
            if (t.freqHz in blacklist) continue
            val lastVisit = recentVisits[t.freqHz]
            if (lastVisit != null && now - lastVisit < config.cooldownMs) continue
            val bin = CarrierDetector.freqToBin(t.freqHz, frame.size, center, samp) ?: continue
            var maxDb = -Float.MAX_VALUE
            for (i in (bin - halfWin).coerceAtLeast(0)..(bin + halfWin).coerceAtMost(frame.size - 1)) {
                if (frame[i] > maxDb) maxDb = frame[i]
            }
            if (maxDb > floor + config.thresholdDb) {
                if (best == null || maxDb > best.peakDb) {
                    best = Candidate(t.freqHz, maxDb, floor, t.modulation, t.name)
                }
            }
        }
        return best
    }

    private fun lock(c: Candidate, center: Long, samp: Int, now: Long) {
        lockedFreq = c.freq
        lockedSince = now
        lastSeenLocked = now
        state = ScanState.LOCKED
        val offset = (c.freq - center).toInt().coerceIn(-samp / 2, samp / 2)
        // open squelch just above the noise floor so audio passes
        host.tune(offset, squelchLevel = c.floorDb + 5f, mod = c.modulation)
        _hits.tryEmit(ScanHit(c.freq, c.peakDb, host.radioConfig.value.profileId))
        publish("Nasłuch ${c.name ?: ""} %.4f MHz (%.0f dB)".format(c.freq / 1e6, c.peakDb).trim())
    }

    private fun carrierPresent(frame: FloatArray, center: Long, samp: Int): Boolean {
        val bin = CarrierDetector.freqToBin(lockedFreq, frame.size, center, samp) ?: return false
        val floor = CarrierDetector.noiseFloor(frame)
        // check a small window around the locked bin
        val halfWin = (frame.size * config.rasterHz.toFloat() / samp / 2).toInt().coerceAtLeast(2)
        var maxDb = -Float.MAX_VALUE
        for (i in (bin - halfWin).coerceAtLeast(0)..(bin + halfWin).coerceAtMost(frame.size - 1)) {
            if (frame[i] > maxDb) maxDb = frame[i]
        }
        return maxDb > floor + config.thresholdDb - HYSTERESIS_DB
    }

    private fun maybeHopProfile(now: Long) {
        val plan = config.profilePlan
        if (plan.size < 2) return
        if (now - profileEnteredAt < config.safeDwellMs) return
        switchToProfile((profileIndex + 1) % plan.size)
    }

    private fun switchToProfile(index: Int) {
        profileIndex = index
        settleOldCenter = host.radioConfig.value.centerFreq ?: 0
        settleStarted = host.now()
        profileEnteredAt = host.now()
        state = ScanState.SETTLING
        host.selectProfile(config.profilePlan[index])
        publish("Przełączanie na profil ${index + 1}/${config.profilePlan.size}…")
    }

    private fun resumeScan(msg: String) {
        state = ScanState.SCANNING
        candidateFreq = 0
        profileEnteredAt = host.now()
        publish(msg)
    }

    private fun publish(message: String) {
        _status.value = ScannerStatus(
            state = state,
            lockedFreq = if (state == ScanState.LOCKED || state == ScanState.HANG) lockedFreq else null,
            profileIndex = profileIndex,
            message = message,
        )
    }

    companion object {
        const val SETTLE_TIMEOUT_MS = 4000L
        const val CARRIER_DROP_MS = 500L
        const val HYSTERESIS_DB = 3f
        const val EDGE_MARGIN = 0.05f
    }
}
