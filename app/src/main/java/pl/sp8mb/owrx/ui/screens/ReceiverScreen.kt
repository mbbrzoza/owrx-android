package pl.sp8mb.owrx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import pl.sp8mb.owrx.session.OwrxSession
import pl.sp8mb.owrx.ui.receiver.WaterfallView
import pl.sp8mb.owrx.ui.vm.ReceiverViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(vm: ReceiverViewModel = hiltViewModel()) {
    val state by vm.connectionState.collectAsState()
    val config by vm.radioConfig.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val smeter by vm.smeter.collectAsState()
    val backoff by vm.backoff.collectAsState()
    val modes by vm.modes.collectAsState()
    val desired by vm.desired.collectAsState()
    val tunedFreq by vm.tunedFreq.collectAsState()
    val currentMod by vm.currentMod.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()

    var showProfiles by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var waterfall by remember { mutableStateOf<WaterfallView?>(null) }

    // feed FFT frames into the view, update tuning overlay
    LaunchedEffect(waterfall) {
        val view = waterfall ?: return@LaunchedEffect
        vm.fft.collect { view.addFrame(it) }
    }
    LaunchedEffect(config.centerFreq, config.sampRate, tunedFreq, desired, waterfall) {
        waterfall?.let { v ->
            v.centerFreq = config.centerFreq ?: 0
            v.sampRate = config.sampRate ?: 0
            v.passbandLow = desired.lowCut ?: -6000
            v.passbandHigh = desired.highCut ?: 6000
            v.tunedFreq = tunedFreq
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── status / frequency header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (tunedFreq > 0) "%.4f MHz".format(tunedFreq / 1e6) else "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = when (val s = state) {
                        is OwrxSession.ConnectionState.Connected -> "${currentMod.uppercase()} · ${config.profileId ?: ""}"
                        is OwrxSession.ConnectionState.Connecting -> "Łączenie…"
                        is OwrxSession.ConnectionState.Reconnecting -> "Ponawianie #${s.attempt}…"
                        else -> "Rozłączony"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is OwrxSession.ConnectionState.Connected) Color(0xFF7CCB7C) else Color(0xFFE0A040),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "S: %.0f dB".format(smeter),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                LinearProgressIndicator(
                    progress = { ((smeter + 120f) / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.width(90.dp),
                )
            }
        }

        backoff?.let {
            Text(
                "⛔ Serwer ograniczył połączenia: $it",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33FF0000))
                    .padding(8.dp),
            )
        }

        // ── waterfall ──
        AndroidView(
            factory = { ctx ->
                WaterfallView(ctx).also { v ->
                    v.onTune = { freq -> vm.tune(freq) }
                    waterfall = v
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // ── tuning steps ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf(-25000, -12500, -8333, 8333, 12500, 25000).forEach { step ->
                TextButton(onClick = { vm.tuneStep(step) }) {
                    Text(
                        text = (if (step > 0) "+" else "−") + "%.1fk".format(kotlin.math.abs(step) / 1000.0),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // ── modes ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val analogMods = modes.filter { it.analog }.map { it.modulation }
                .ifEmpty { listOf("nfm", "am", "usb", "lsb", "cw") }
            analogMods.forEach { mod ->
                FilterChip(
                    selected = currentMod == mod,
                    onClick = { vm.setMode(mod) },
                    label = { Text(mod.uppercase()) },
                )
            }
        }

        // ── squelch ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SQ", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = desired.squelchLevel ?: -150f,
                onValueChange = { vm.setSquelch(it) },
                valueRange = -150f..0f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%.0f".format(desired.squelchLevel ?: -150f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        // ── bottom bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { showProfiles = true }, modifier = Modifier.weight(1f)) {
                Text("Profil")
            }
            OutlinedButton(onClick = { showBookmarks = true }, modifier = Modifier.weight(1f)) {
                Text("Zakładki (${bookmarks.size})")
            }
        }
    }

    if (showProfiles) {
        ModalBottomSheet(onDismissRequest = { showProfiles = false }) {
            LazyColumn {
                items(profiles) { p ->
                    TextButton(
                        onClick = {
                            showProfiles = false
                            vm.selectProfile(p.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(p.name) }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            LazyColumn {
                items(bookmarks) { b ->
                    TextButton(
                        onClick = {
                            showBookmarks = false
                            vm.tune(b.frequency)
                            b.modulation?.let { vm.setMode(it) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${b.name} — %.4f MHz".format(b.frequency / 1e6))
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
