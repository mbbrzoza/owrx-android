package pl.sp8mb.owrx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import pl.sp8mb.owrx.session.OwrxSession
import pl.sp8mb.owrx.ui.vm.DebugViewModel

@Composable
fun DebugScreen(vm: DebugViewModel = hiltViewModel()) {
    val url by vm.urlInput.collectAsState()
    val state by vm.connectionState.collectAsState()
    val config by vm.radioConfig.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val smeter by vm.smeter.collectAsState()
    val backoff by vm.backoff.collectAsState()
    val logLines by vm.logLines.collectAsState()

    var profileMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { vm.urlInput.value = it },
            label = { Text("Serwer (host lub ws[s]://...)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::connect, enabled = state is OwrxSession.ConnectionState.Disconnected) {
                Text("Połącz")
            }
            OutlinedButton(onClick = vm::disconnect, enabled = state !is OwrxSession.ConnectionState.Disconnected) {
                Text("Rozłącz")
            }
        }

        Text(
            text = when (val s = state) {
                is OwrxSession.ConnectionState.Disconnected -> "Rozłączony"
                is OwrxSession.ConnectionState.Connecting -> "Łączenie z ${s.url}…"
                is OwrxSession.ConnectionState.Connected -> "Połączony (OpenWebRX ${s.serverVersion})"
                is OwrxSession.ConnectionState.Reconnecting -> "Ponawianie #${s.attempt} za ${s.delayMs / 1000}s"
            },
            style = MaterialTheme.typography.bodyLarge,
        )

        backoff?.let {
            Text("⛔ BACKOFF: $it", color = MaterialTheme.colorScheme.error)
        }

        config.centerFreq?.let { cf ->
            Text("Częstotliwość środkowa: ${"%.4f".format(cf / 1e6)} MHz, samp_rate: ${config.sampRate}")
            Text("FFT: ${config.fftSize} (${config.fftCompression}), audio: ${config.audioCompression}")
            Text("Profil: ${config.profileId ?: "?"}")
        }

        Text("S-meter: ${"%.1f".format(smeter)} dB")

        if (profiles.isNotEmpty()) {
            OutlinedButton(onClick = { profileMenu = true }) {
                Text("Profil (${profiles.size})")
            }
            DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                profiles.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = {
                            profileMenu = false
                            vm.selectProfile(p.id)
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("am", "nfm", "usb", "lsb").forEach { mod ->
                OutlinedButton(onClick = { vm.setMod(mod) }) { Text(mod.uppercase()) }
            }
        }

        Text("Log:", style = MaterialTheme.typography.titleSmall)
        logLines.takeLast(15).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}
