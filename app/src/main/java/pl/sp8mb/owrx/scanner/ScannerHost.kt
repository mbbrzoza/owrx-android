package pl.sp8mb.owrx.scanner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import pl.sp8mb.owrx.session.OwrxSession
import pl.sp8mb.owrx.session.RadioConfig
import javax.inject.Inject

/** What the scanner needs from the radio — abstracted for JVM tests. */
interface ScannerHost {
    val fft: Flow<FloatArray>
    val radioConfig: StateFlow<RadioConfig>
    val backoff: StateFlow<String?>
    fun tune(offsetFreq: Int, squelchLevel: Float?)
    fun selectProfile(id: String)
    fun now(): Long
}

class SessionScannerHost @Inject constructor(
    private val session: OwrxSession,
) : ScannerHost {
    override val fft get() = session.fft
    override val radioConfig get() = session.radioConfig
    override val backoff get() = session.backoff
    override fun tune(offsetFreq: Int, squelchLevel: Float?) =
        session.setDsp(offsetFreq = offsetFreq, squelchLevel = squelchLevel)

    override fun selectProfile(id: String) = session.selectProfile(id)
    override fun now(): Long = System.currentTimeMillis()
}
