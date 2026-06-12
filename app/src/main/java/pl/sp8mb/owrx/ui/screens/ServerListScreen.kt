package pl.sp8mb.owrx.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import pl.sp8mb.owrx.data.db.ServerEntity
import pl.sp8mb.owrx.ui.vm.ServerListViewModel

@Composable
fun ServerListScreen(
    onConnected: () -> Unit,
    vm: ServerListViewModel = hiltViewModel(),
) {
    val servers by vm.servers.collectAsState()
    var editing by remember { mutableStateOf<ServerEntity?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = null
                showDialog = true
            }) { Icon(Icons.Default.Add, contentDescription = "Dodaj") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(servers, key = { it.id }) { server ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.connect(server)
                            onConnected()
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleMedium)
                            Text(server.address, style = MaterialTheme.typography.bodySmall)
                            if (server.username != null) {
                                Text("🔒 basic auth", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton(onClick = {
                            editing = server
                            showDialog = true
                        }) { Icon(Icons.Default.Edit, contentDescription = "Edytuj") }
                        IconButton(onClick = { vm.delete(server) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Usuń")
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        ServerEditDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = {
                vm.save(it)
                showDialog = false
            },
        )
    }
}

@Composable
private fun ServerEditDialog(
    initial: ServerEntity?,
    onDismiss: () -> Unit,
    onSave: (ServerEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nowy serwer" else "Edytuj serwer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nazwa") }, singleLine = true)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Adres (host, host:port lub ws[s]://…)") },
                    singleLine = true,
                )
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Login (opcjonalnie)") }, singleLine = true)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Hasło (opcjonalnie)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && address.isNotBlank()) {
                        onSave(
                            (initial ?: ServerEntity(name = "", address = "")).copy(
                                name = name.trim(),
                                address = address.trim(),
                                username = username.trim().ifEmpty { null },
                                password = password.ifEmpty { null },
                            )
                        )
                    }
                },
            ) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
