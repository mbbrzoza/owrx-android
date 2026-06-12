package pl.sp8mb.owrx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import pl.sp8mb.owrx.scanner.ScanState
import pl.sp8mb.owrx.ui.vm.ScannerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScannerScreen(vm: ScannerViewModel = hiltViewModel()) {
    val status by vm.status.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val hits by vm.hits.collectAsState()
    val threshold by vm.thresholdDb.collectAsState()
    val raster by vm.rasterHz.collectAsState()
    val selectedProfiles by vm.selectedProfiles.collectAsState()

    val running = status.state != ScanState.IDLE
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // ── status + controls ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (running) vm.stop() else vm.start() }) {
                Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Text(if (running) " Stop" else " Start")
            }
            OutlinedButton(onClick = vm::skip, enabled = status.state == ScanState.LOCKED || status.state == ScanState.HANG) {
                Icon(Icons.Default.SkipNext, null)
                Text(" Pomiń")
            }
        }

        Text(
            text = status.message.ifEmpty { "Gotowy" },
            color = when (status.state) {
                ScanState.LOCKED -> Color(0xFF7CCB7C)
                ScanState.HANG -> Color(0xFFE0C040)
                ScanState.SCANNING, ScanState.SETTLING -> Color(0xFF64B5F6)
                ScanState.IDLE -> Color.Gray
            },
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )

        // ── parameters ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Próg ", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = threshold,
                onValueChange = { vm.thresholdDb.value = it },
                valueRange = 6f..30f,
                enabled = !running,
                modifier = Modifier.weight(1f),
            )
            Text("%.0f dB".format(threshold), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(8333 to "8.33k", 12500 to "12.5k", 25000 to "25k").forEach { (hz, label) ->
                FilterChip(
                    selected = raster == hz,
                    onClick = { if (!running) vm.rasterHz.value = hz },
                    label = { Text(label) },
                )
            }
        }

        // ── profile plan ──
        if (profiles.isNotEmpty()) {
            Text("Plan profili (puste = tylko bieżący):", style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                profiles.forEach { p ->
                    FilterChip(
                        selected = p.id in selectedProfiles,
                        onClick = { if (!running) vm.toggleProfile(p.id) },
                        label = { Text(p.name) },
                    )
                }
            }
            if (selectedProfiles.size > 1) {
                Text(
                    "⚠ Przełączanie profili zmienia odbiornik WSZYSTKIM słuchaczom (min. 10 s/profil)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE0A040),
                )
            }
        }

        // ── hits ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trafienia (${hits.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = vm::clearHits) { Text("Wyczyść") }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(hits, key = { it.id }) { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (hit.blacklisted) Color(0x33FF0000) else Color.Transparent)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "%.4f MHz".format(hit.freqHz / 1e6),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "×${hit.hitCount} · ost. ${timeFmt.format(Date(hit.lastHeard))}" +
                                (hit.label?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                    OutlinedButton(onClick = { vm.tuneTo(hit) }) { Text("Strój") }
                    IconButton(onClick = { vm.toggleBlacklist(hit) }) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = "Blacklista",
                            tint = if (hit.blacklisted) Color.Red else Color.Gray,
                        )
                    }
                }
            }
        }
    }
}
