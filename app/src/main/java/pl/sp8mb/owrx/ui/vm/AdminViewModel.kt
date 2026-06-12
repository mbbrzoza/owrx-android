package pl.sp8mb.owrx.ui.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.sp8mb.owrx.admin.AdminClient
import pl.sp8mb.owrx.data.db.ServerDao
import javax.inject.Inject

@HiltViewModel
class AdminViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val serverDao: ServerDao,
) : ViewModel() {

    sealed class Page {
        data object Loading : Page()
        data class Error(val message: String) : Page()
        data class Devices(val devices: List<Pair<String, String>>) : Page()
        data class Form(val path: String, val page: AdminClient.FormPage) : Page()
    }

    private val serverId: Long = savedState.get<Long>("serverId") ?: -1

    val page = MutableStateFlow<Page>(Page.Loading)
    val busy = MutableStateFlow(false)
    val toast = MutableStateFlow<String?>(null)
    val serverName = MutableStateFlow("")

    private var client: AdminClient? = null
    private val backStack = ArrayDeque<Page>()

    init {
        viewModelScope.launch { connect() }
    }

    private suspend fun connect() {
        page.value = Page.Loading
        try {
            val server = serverDao.byId(serverId)
                ?: throw IllegalStateException("Nie znaleziono serwera")
            serverName.value = server.name
            if (server.adminUser.isNullOrEmpty()) {
                throw IllegalStateException("Brak danych logowania admina — uzupełnij w edycji serwera")
            }
            withContext(Dispatchers.IO) {
                client = AdminClient(
                    baseUrl = server.httpUrl(),
                    username = server.adminUser,
                    password = server.adminPassword ?: "",
                    basicAuthUser = server.username,
                    basicAuthPassword = server.password,
                ).also { it.login() }
            }
            loadDevices()
        } catch (e: Exception) {
            page.value = Page.Error(e.message ?: "Błąd połączenia")
        }
    }

    fun loadDevices() {
        val c = client ?: return
        viewModelScope.launch {
            page.value = Page.Loading
            try {
                val devices = withContext(Dispatchers.IO) { c.listDevices() }
                backStack.clear()
                page.value = Page.Devices(devices)
            } catch (e: Exception) {
                page.value = Page.Error(e.message ?: "Błąd")
            }
        }
    }

    fun open(path: String) {
        val c = client ?: return
        viewModelScope.launch {
            val prev = page.value
            page.value = Page.Loading
            try {
                val form = withContext(Dispatchers.IO) { c.loadForm(path) }
                if (prev !is Page.Loading) backStack.addLast(prev)
                page.value = Page.Form(path, form)
            } catch (e: Exception) {
                page.value = Page.Error(e.message ?: "Błąd")
            }
        }
    }

    fun back(): Boolean {
        val prev = backStack.removeLastOrNull() ?: return false
        page.value = prev
        return true
    }

    fun updateField(name: String, value: String? = null, checked: Boolean? = null) {
        val current = page.value as? Page.Form ?: return
        page.value = current.copy(
            page = current.page.copy(
                fields = current.page.fields.map {
                    if (it.name == name) it.copy(
                        value = value ?: it.value,
                        checked = checked ?: it.checked,
                    ) else it
                }
            )
        )
    }

    fun submit() {
        val c = client ?: return
        val current = page.value as? Page.Form ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                withContext(Dispatchers.IO) { c.submitForm(current.page.action, current.page.fields) }
                toast.value = "Zapisano"
                // reload the form to reflect server-side state
                val form = withContext(Dispatchers.IO) { c.loadForm(current.path) }
                page.value = Page.Form(current.path, form)
            } catch (e: Exception) {
                toast.value = e.message ?: "Błąd zapisu"
            } finally {
                busy.value = false
            }
        }
    }

    fun clearToast() {
        toast.value = null
    }
}
