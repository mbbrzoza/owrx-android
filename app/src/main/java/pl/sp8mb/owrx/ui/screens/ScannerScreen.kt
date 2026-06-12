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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import pl.sp8mb.owrx.ui.vm.ScanModeUi
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

        // ── scan mode ──
        val scanMode by vm.scanModeUi.collectAsState()
        val rangeStart by vm.rangeStartMhz.collectAsState()
        val rangeEnd by vm.rangeEndMhz.collectAsState()
        val favorites by vm.favorites.collectAsState()
        val startError by vm.startError.collectAsState()

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                ScanModeUi.FULL_BAND to "Całe pasmo",
                ScanModeUi.RANGE to "Zakres",
                ScanModeUi.FAVORITES to "Zakładki (${favorites.size})",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = scanMode == mode,
                    onClick = { if (!running) vm.scanModeUi.value = mode },
                    label = { Text(label) },
                )
            }
        }

        if (scanMode == ScanModeUi.RANGE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = rangeStart,
                    onValueChange = { vm.rangeStartMhz.value = it },
                    label = { Text("Od (MHz)") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = rangeEnd,
                    onValueChange = { vm.rangeEndMhz.value = it },
                    label = { Text("Do (MHz)") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (scanMode == ScanModeUi.FAVORITES) {
            val selectedFavs by vm.selectedFavorites.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Wybierz zakładki (${selectedFavs.size}/${favorites.size}):",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.selectAllFavorites(true) }, enabled = !running) { Text("Wszystkie") }
                TextButton(onClick = { vm.selectAllFavorites(false) }, enabled = !running) { Text("Żadna") }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp),
            ) {
                items(favorites.sortedBy { it.freqHz }, key = { it.freqHz }) { fav ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = fav.freqHz in selectedFavs,
                            onCheckedChange = { if (!running) vm.toggleFavorite(fav.freqHz) },
                            enabled = !running,
                        )
                        Text(
                            "${fav.name} — %.4f MHz".format(fav.freqHz / 1e6) +
                                (fav.modulation?.let { " ${it.uppercase()}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        startError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

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
