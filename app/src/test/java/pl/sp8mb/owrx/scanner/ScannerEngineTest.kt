package pl.sp8mb.owrx.scanner

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.sp8mb.owrx.session.RadioConfig

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerEngineTest {

    private class FakeHost : ScannerHost {
        var time = 0L
        val fftFlow = MutableSharedFlow<FloatArray>(extraBufferCapacity = 16)
        val configFlow = MutableStateFlow(
            RadioConfig().merge(
                kotlinx.serialization.json.buildJsonObject {
                    put("center_freq", kotlinx.serialization.json.JsonPrimitive(136_000_000L))
                    put("samp_rate", kotlinx.serialization.json.JsonPrimitive(2_400_000))
                    put("profile_id", kotlinx.serialization.json.JsonPrimitive("p1"))
                }
            )
        )
        val backoffFlow = MutableStateFlow<String?>(null)
        val tuneCalls = ArrayList<Pair<Int, Float?>>()
        val profileCalls = ArrayList<String>()

        override val fft get() = fftFlow
        override val radioConfig: StateFlow<RadioConfig> get() = configFlow
        override val backoff: StateFlow<String?> get() = backoffFlow
        override fun tune(offsetFreq: Int, squelchLevel: Float?) {
            tuneCalls.add(offsetFreq to squelchLevel)
        }

        override fun selectProfile(id: String) {
            profileCalls.add(id)
        }

        override fun now(): Long = time
    }

    private fun frame(carrierBin: Int? = null, bins: Int = 4096): FloatArray {
        val f = FloatArray(bins) { -100f }
        if (carrierBin != null) {
            for (i in carrierBin - 2..carrierBin + 2) f[i] = -60f
        }
        return f
    }

    @Test
    fun `locks on confirmed carrier and resumes after hang`() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeHost()
        val engine = ScannerEngine(host, backgroundScope)
        engine.start(ScannerConfig(thresholdDb = 12f, rasterHz = 12500, confirmMs = 400, hangMs = 3000))

        // candidate appears at bin 2048 (≈136.000 MHz after raster snap)
        host.fftFlow.emit(frame(2048))
        assertEquals(ScanState.SCANNING, engine.status.value.state)

        // confirmed after confirmMs
        host.time = 500
        host.fftFlow.emit(frame(2048))
        assertEquals(ScanState.LOCKED, engine.status.value.state)
        assertEquals(1, host.tuneCalls.size)
        val (offset, squelch) = host.tuneCalls[0]
        assertEquals(0, offset) // 136.000 MHz == center
        assertTrue("squelch $squelch", squelch != null && squelch > -100f && squelch < -60f)

        // carrier drops → HANG after CARRIER_DROP_MS
        host.time = 1000
        host.fftFlow.emit(frame(null))
        host.time = 1600
        host.fftFlow.emit(frame(null))
        assertEquals(ScanState.HANG, engine.status.value.state)

        // carrier returns during hang → re-LOCK
        host.time = 2000
        host.fftFlow.emit(frame(2048))
        assertEquals(ScanState.LOCKED, engine.status.value.state)

        // drops again, hang expires → SCANNING with cooldown
        host.time = 3000
        host.fftFlow.emit(frame(null))
        host.time = 3600
        host.fftFlow.emit(frame(null))
        assertEquals(ScanState.HANG, engine.status.value.state)
        host.time = 7000
        host.fftFlow.emit(frame(null))
        assertEquals(ScanState.SCANNING, engine.status.value.state)

        // cooldown: same carrier immediately after release is ignored
        host.time = 7100
        host.fftFlow.emit(frame(2048))
        host.time = 8000
        host.fftFlow.emit(frame(2048))
        assertEquals(ScanState.SCANNING, engine.status.value.state)
    }

    @Test
    fun `blacklisted frequency is never locked`() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeHost()
        val engine = ScannerEngine(host, backgroundScope)
        engine.blacklist = setOf(136_000_000L)
        engine.start(ScannerConfig(confirmMs = 0))

        host.fftFlow.emit(frame(2048))
        host.time = 1000
        host.fftFlow.emit(frame(2048))
        assertEquals(ScanState.SCANNING, engine.status.value.state)
        assertEquals(0, host.tuneCalls.size)
    }

    @Test
    fun `profile plan hops after dwell with bot-ban floor`() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeHost()
        val engine = ScannerEngine(host, backgroundScope)
        // dwellMs below the floor must be clamped to 10 s
        engine.start(ScannerConfig(profilePlan = listOf("a|1", "b|2"), dwellMs = 1000))

        assertEquals(listOf("a|1"), host.profileCalls)
        assertEquals(ScanState.SETTLING, engine.status.value.state)

        // config arrives for the new profile → SCANNING
        host.configFlow.value = host.configFlow.value.merge(
            kotlinx.serialization.json.buildJsonObject {
                put("center_freq", kotlinx.serialization.json.JsonPrimitive(120_000_000L))
            }
        )
        host.time = 100
        host.fftFlow.emit(frame(null))
        assertEquals(ScanState.SCANNING, engine.status.value.state)

        // before 10 s: no hop even though dwellMs=1000
        host.time = 5000
        host.fftFlow.emit(frame(null))
        assertEquals(1, host.profileCalls.size)

        // after the 10 s floor: hop to second profile
        host.time = 10_200
        host.fftFlow.emit(frame(null))
        assertEquals(listOf("a|1", "b|2"), host.profileCalls)
        assertEquals(ScanState.SETTLING, engine.status.value.state)
    }

    @Test
    fun `backoff message stops the scanner`() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeHost()
        val engine = ScannerEngine(host, backgroundScope)
        engine.start(ScannerConfig())
        host.backoffFlow.value = "rate limited"
        host.fftFlow.emit(frame(2048))
        assertEquals(ScanState.IDLE, engine.status.value.state)
    }
}
