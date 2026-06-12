package pl.sp8mb.owrx.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import pl.sp8mb.owrx.admin.AdminClient
import pl.sp8mb.owrx.ui.vm.AdminViewModel

@Composable
fun AdminScreen(onExit: () -> Unit, vm: AdminViewModel = hiltViewModel()) {
    val page by vm.page.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val serverName by vm.serverName.collectAsState()

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2500)
            vm.clearToast()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (!vm.back()) onExit() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
            }
            Text(
                "Admin: $serverName",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::loadDevices) {
                Icon(Icons.Default.Refresh, contentDescription = "Urządzenia")
            }
        }

        toast?.let {
            Text(
                it,
                color = if (it.startsWith("Zapisano")) Color(0xFF7CCB7C) else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        when (val p = page) {
            is AdminViewModel.Page.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is AdminViewModel.Page.Error -> Column(modifier = Modifier.padding(16.dp)) {
                Text(p.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = vm::loadDevices, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Spróbuj ponownie")
                }
            }

            is AdminViewModel.Page.Devices -> LazyColumn(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (p.devices.isEmpty()) {
                    item { Text("Brak urządzeń SDR (albo brak uprawnień)") }
                }
                items(p.devices) { (path, label) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.open(path) },
                    ) {
                        Text(label, modifier = Modifier.padding(16.dp))
                    }
                }
            }

            is AdminViewModel.Page.Form -> FormView(
                page = p.page,
                busy = busy,
                onField = vm::updateField,
                onOpen = vm::open,
                onSubmit = vm::submit,
            )
        }
    }
}

@Composable
private fun FormView(
    page: AdminClient.FormPage,
    busy: Boolean,
    onField: (String, String?, Boolean?) -> Unit,
    onOpen: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (page.links.isNotEmpty()) {
            item {
                Text("Profile / podstrony:", style = MaterialTheme.typography.titleSmall)
            }
            items(page.links) { (path, label) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(path) },
                ) {
                    Text(
                        if (path.endsWith("/newprofile")) "➕ $label" else label,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        items(page.fields.filter { it.type != "hidden" }) { f ->
            when (f.type) {
                "checkbox" -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(f.label, modifier = Modifier.weight(1f))
                    Switch(
                        checked = f.checked,
                        onCheckedChange = { onField(f.name, null, it) },
                    )
                }

                "select" -> {
                    var expanded by remember(f.name) { mutableStateOf(false) }
                    Column {
                        Text(f.label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        OutlinedButton(onClick = { expanded = true }) {
                            Text(f.options.firstOrNull { it.first == f.value }?.second ?: f.value)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            f.options.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        expanded = false
                                        onField(f.name, value, null)
                                    },
                                )
                            }
                        }
                    }
                }

                else -> OutlinedTextField(
                    value = f.value,
                    onValueChange = { onField(f.name, it, null) },
                    label = { Text(f.label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Button(
                onClick = onSubmit,
                enabled = !busy && page.fields.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                Text(if (busy) "Zapisywanie…" else "Zapisz")
            }
        }
    }
}
