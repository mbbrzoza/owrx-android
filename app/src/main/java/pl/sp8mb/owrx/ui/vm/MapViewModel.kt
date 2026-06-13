package pl.sp8mb.owrx.ui.vm

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import pl.sp8mb.owrx.map.MapRepository
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repo: MapRepository,
) : ViewModel() {

    val positions = repo.positions
    val receiver = repo.receiver
    val connected = repo.connected

    fun connect() = repo.connect()
    fun disconnect() = repo.disconnect()
}
