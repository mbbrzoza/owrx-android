package pl.sp8mb.owrx.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import pl.sp8mb.owrx.service.OwrxForegroundService
import pl.sp8mb.owrx.session.OwrxSession
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    app: Application,
    val session: OwrxSession,
) : AndroidViewModel(app) {

    val urlInput = MutableStateFlow("wss://sdr.inter1.pl/ws/")

    val connectionState = session.connectionState
    val radioConfig = session.radioConfig
    val profiles = session.profiles
    val smeter = session.smeter
    val backoff = session.backoff

    val logLines: StateFlow<List<String>> = session.log
        .scan(emptyList<String>()) { acc, line -> (acc + line).takeLast(50) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun connect() {
        val raw = urlInput.value.trim()
        val url = when {
            raw.startsWith("ws://") || raw.startsWith("wss://") -> raw
            raw.contains(":") -> "ws://$raw/ws/"
            else -> "wss://$raw/ws/"
        }
        OwrxForegroundService.start(getApplication())
        session.connect(url)
    }

    fun disconnect() {
        session.disconnect()
        OwrxForegroundService.stop(getApplication())
    }

    fun selectProfile(id: String) = session.selectProfile(id)

    fun setMod(mod: String) = session.setDsp(mod = mod)
}
