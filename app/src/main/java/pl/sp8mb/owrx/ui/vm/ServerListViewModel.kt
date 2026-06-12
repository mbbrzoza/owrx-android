package pl.sp8mb.owrx.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.sp8mb.owrx.data.db.ServerDao
import pl.sp8mb.owrx.data.db.ServerEntity
import pl.sp8mb.owrx.data.prefs.UserPreferences
import pl.sp8mb.owrx.service.OwrxForegroundService
import pl.sp8mb.owrx.session.OwrxSession
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
    app: Application,
    private val serverDao: ServerDao,
    private val prefs: UserPreferences,
    private val session: OwrxSession,
) : AndroidViewModel(app) {

    val servers: StateFlow<List<ServerEntity>> = serverDao.all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val connectionState = session.connectionState

    fun save(server: ServerEntity) {
        viewModelScope.launch { serverDao.upsert(server) }
    }

    fun delete(server: ServerEntity) {
        viewModelScope.launch { serverDao.delete(server) }
    }

    /** Connects and returns immediately; caller navigates to the receiver screen. */
    fun connect(server: ServerEntity) {
        viewModelScope.launch {
            prefs.setLastServer(server.id)
            prefs.desiredState.first()?.let { session.restoreDesiredState(it) }
            session.magicKey = server.magicKey
            OwrxForegroundService.start(getApplication())
            session.connect(server.wsUrl(), server.username, server.password)
        }
    }

    fun disconnect() {
        session.disconnect()
        OwrxForegroundService.stop(getApplication())
    }
}
