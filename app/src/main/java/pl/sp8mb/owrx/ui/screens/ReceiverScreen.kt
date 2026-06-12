package pl.sp8mb.owrx.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import pl.sp8mb.owrx.data.prefs.Favorite
import pl.sp8mb.owrx.session.OwrxSession
import pl.sp8mb.owrx.ui.receiver.WaterfallView
import pl.sp8mb.owrx.ui.vm.ReceiverViewModel

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
    val favorites by vm.favorites.collectAsState()

    var leftOpen by remember { mutableStateOf(false) }
    var rightOpen by remember { mutableStateOf(false) }
    var showAddFavorite by remember { mutableStateOf(false) }
    var showFreqDialog by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showDigi by remember { mutableStateOf(false) }
    var waterfall by remember { mutableStateOf<WaterfallView?>(null) }

    LaunchedEffect(waterfall) {
        val view = waterfall ?: return@LaunchedEffect
        vm.fft.collect { view.addFrame(it) }
    }
    LaunchedEffect(config.centerFreq, config.sampRate, tunedFreq, desired, bookmarks, favorites, waterfall) {
        waterfall?.let { v ->
            v.centerFreq = config.centerFreq ?: 0
            v.sampRate = config.sampRate ?: 0
            v.passbandLow = desired.lowCut ?: -6000
            v.passbandHigh = desired.highCut ?: 6000
            // server bookmarks merged with our favorites (server wins on same freq),
            // so bands without server-side bookmarks still show everything we know
            v.tags = (
                bookmarks.map { WaterfallView.Tag(it.frequency, it.name, it.modulation) } +
                    favorites.map { WaterfallView.Tag(it.freqHz, it.name, it.modulation) }
                ).distinctBy { it.freq }
            v.tunedFreq = tunedFreq
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize()) {

            // ── status / frequency header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // digital frequency display — tap to type any frequency
                    Text(
                        text = if (tunedFreq > 0) "%.4f MHz".format(tunedFreq / 1e6) else "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF9CE0FF),
                        modifier = Modifier.clickable { showFreqDialog = true },
                    )
                    Text(
                        text = when (val s = state) {
                            is OwrxSession.ConnectionState.Connected ->
                                "${currentMod.uppercase()} · ${profiles.firstOrNull { it.id == config.fullProfileId }?.name ?: config.profileId ?: ""}"
                            is OwrxSession.ConnectionState.Connecting -> "Łączenie…"
                            is OwrxSession.ConnectionState.Reconnecting -> "Ponawianie #${s.attempt}…"
                            else -> "Rozłączony"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is OwrxSession.ConnectionState.Connected) Color(0xFF7CCB7C) else Color(0xFFE0A040),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val clients by vm.clientCount.collectAsState()
                if (clients > 0) {
                    Text(
                        "👥 $clients",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clickable { showChat = true }
                            .padding(end = 12.dp),
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

            // ── "who's transmitting" for digital voice (DMR/YSF/D-STAR/NXDN) ──
            val dv by vm.digitalVoice.collectAsState()
            dv?.let { a ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x2240C040))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "🔊 ${a.protocol}" + (a.slot?.let { " S$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7CCB7C),
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "  ${a.line1}" + (a.line2?.let { " $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ── frequency scale + waterfall ──
            AndroidView(
                factory = { ctx ->
                    WaterfallView(ctx).also { v ->
                        v.onTune = { freq -> vm.tune(freq) }
                        v.onTagTap = { tag ->
                            vm.tune(tag.freq)
                            tag.modulation?.let { vm.setMode(it) }
                        }
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

                // digimode (secondary demod) picker
                val digiModes by vm.digiModes.collectAsState()
                val activeDigi by vm.secondaryMod.collectAsState()
                if (digiModes.isNotEmpty()) {
                    var digiMenu by remember { mutableStateOf(false) }
                    Box {
                        FilterChip(
                            selected = activeDigi != null,
                            onClick = { digiMenu = true },
                            label = { Text(activeDigi?.uppercase() ?: "DIGI") },
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = digiMenu,
                            onDismissRequest = { digiMenu = false },
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("— wyłącz —") },
                                onClick = { digiMenu = false; vm.setDigimode(null); showDigi = false },
                            )
                            digiModes.forEach { dm ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(dm.name) },
                                    onClick = {
                                        digiMenu = false
                                        vm.setDigimode(dm.modulation)
                                        showDigi = true
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── single toolbar: mute | SQ | AGC/gain | NR | REC | colours ──
            val muted by vm.muted.collectAsState()
            val gain by vm.gainState.collectAsState()
            val recState by vm.recorderState.collectAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var showVolume by remember { mutableStateOf(false) }
                Box {
                    androidx.compose.material3.IconButton(
                        onClick = vm::toggleMute,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = { showVolume = true })
                        },
                    ) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Wycisz (przytrzymaj = głośność)",
                            tint = if (muted) Color(0xFFFF6060) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showVolume,
                        onDismissRequest = { showVolume = false },
                    ) {
                        val vol by vm.volume.collectAsState()
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp).width(200.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Głośność", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = vol,
                                onValueChange = { vm.setVolume(it) },
                                valueRange = 0f..2f,
                                modifier = Modifier.weight(1f),
                            )
                            Text("%.0f%%".format(vol * 100), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
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
                TextButton(onClick = vm::autoSquelch, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("Auto") }

                gain?.let { g ->
                    FilterChip(
                        selected = g.auto,
                        onClick = { vm.setGainAuto(!g.auto) },
                        label = { Text("AGC") },
                    )
                    TextButton(
                        onClick = { vm.adjustGain(-1f) },
                        enabled = !g.auto,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                    ) { Text("−") }
                    Text(
                        if (g.auto) "auto" else "%.0f".format(g.manual),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (g.auto) Color.Gray else MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(
                        onClick = { vm.adjustGain(+1f) },
                        enabled = !g.auto,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                    ) { Text("+") }
                }

                FilterChip(
                    selected = desired.nrEnabled == true,
                    onClick = { vm.setNr(desired.nrEnabled != true, desired.nrThreshold ?: 4) },
                    label = { Text("NR") },
                )
                if (desired.nrEnabled == true) {
                    // tap cycles the NR threshold
                    TextButton(
                        onClick = {
                            val next = when (desired.nrThreshold ?: 4) {
                                2 -> 4; 4 -> 8; 8 -> 16; 16 -> 24; else -> 2
                            }
                            vm.setNr(true, next)
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                    ) {
                        Text("${desired.nrThreshold ?: 4}", fontFamily = FontFamily.Monospace)
                    }
                }

                val voxOn by vm.voxEnabled.collectAsState()
                FilterChip(
                    selected = voxOn,
                    onClick = vm::toggleVox,
                    label = { Text("VOX") },
                )
                // fixed-width slot so the toolbar never reflows when REC starts
                Box(modifier = Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                    if (recState.recording) {
                        var elapsed by remember { mutableStateOf(0L) }
                        LaunchedEffect(recState.startedAt) {
                            while (true) {
                                elapsed = (System.currentTimeMillis() - recState.startedAt) / 1000
                                kotlinx.coroutines.delay(1000)
                            }
                        }
                        Text(
                            (if (recState.vox) "◉" else "") + "%d:%02d".format(elapsed / 60, elapsed % 60),
                            color = Color(0xFFFF6060),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                androidx.compose.material3.IconButton(onClick = vm::toggleRecording) {
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = "Nagrywaj",
                        tint = if (recState.recording) Color(0xFFFF3030) else Color.Gray,
                    )
                }
                androidx.compose.material3.IconButton(onClick = { waterfall?.snapLevels() }) {
                    Icon(Icons.Default.Palette, contentDescription = "Kolory")
                }
            }
        }

        val ioMsg: String? by vm.ioMessage.collectAsState()
        ioMsg?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                vm.ioMessage.value = null
            }
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            ) {
                Surface(color = Color(0xE0303840), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        msg,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        val activeDigiMod by vm.secondaryMod.collectAsState()
        if (showDigi && activeDigiMod != null) {
            val digiMsgs by vm.digiMessages.collectAsState()
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(200.dp),
                color = Color(0xF0141A20),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${activeDigiMod?.uppercase()} — ${digiMsgs.size} wiadomości",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(onClick = { showDigi = false }) { Text("Ukryj") }
                    }
                    LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                        items(digiMsgs.reversed()) { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }

        if (showChat) {
            val chat by vm.chat.collectAsState()
            ChatDialog(
                messages = chat,
                onSend = { vm.sendChat(it) },
                onDismiss = { showChat = false },
            )
        }

        if (showFreqDialog) {
            val canMove by vm.canMoveCenter.collectAsState()
            FreqEntryDialog(
                currentMhz = if (tunedFreq > 0) "%.4f".format(tunedFreq / 1e6).replace(',', '.') else "",
                canMoveCenter = canMove,
                onDismiss = { showFreqDialog = false },
                onTune = { mhz ->
                    showFreqDialog = false
                    vm.tuneAnywhere((mhz * 1e6).toLong())
                },
                onMoveCenter = { mhz ->
                    showFreqDialog = false
                    vm.moveCenter((mhz * 1e6).toLong())
                },
            )
        }

        // ── scrim when a drawer is open ──
        if (leftOpen || rightOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable {
                        leftOpen = false
                        rightOpen = false
                    },
            )
        }

        // ── left drawer: profiles ──
        DrawerHandle(
            visible = !leftOpen && !rightOpen,
            alignment = Alignment.CenterStart,
            icon = { Icon(Icons.Default.ChevronRight, contentDescription = "Profile") },
            onClick = { leftOpen = true },
        )
        AnimatedVisibility(
            visible = leftOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            DrawerPanel(title = "Profile") {
                LazyColumn {
                    items(profiles) { p ->
                        val active = p.id == config.fullProfileId
                        Text(
                            text = (if (active) "▶ " else "") + p.name,
                            color = if (active) Color(0xFF7CCB7C) else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    leftOpen = false
                                    vm.selectProfile(p.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        // ── right drawer: favorites (cross-band jump allowed) ──
        DrawerHandle(
            visible = !leftOpen && !rightOpen,
            alignment = Alignment.CenterEnd,
            icon = { Icon(Icons.Default.ChevronLeft, contentDescription = "Ulubione") },
            onClick = { rightOpen = true },
        )
        if (showAddFavorite) {
            AddFavoriteDialog(
                freqLabel = "%.4f MHz %s".format(tunedFreq / 1e6, currentMod.uppercase()),
                onDismiss = { showAddFavorite = false },
                onSave = { name ->
                    vm.addFavorite(name)
                    showAddFavorite = false
                },
            )
        }

        AnimatedVisibility(
            visible = rightOpen,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            DrawerPanel(title = "Ulubione (${favorites.size})") {
                // export/import backup
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = vm::exportFavorites) { Text("Eksport") }
                    TextButton(onClick = vm::importFavorites) { Text("Import") }
                }
                // save the currently tuned frequency as a new favorite
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAddFavorite = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFE0C040))
                    Text(
                        "  Dodaj bieżącą: %.4f MHz %s".format(tunedFreq / 1e6, currentMod.uppercase()),
                        color = Color(0xFFE0C040),
                    )
                }
                if (favorites.isEmpty()) {
                    Text(
                        "Brak — zakładki zbierają się automatycznie z odwiedzanych profili",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn {
                    items(favorites.sortedBy { it.freqHz }) { fav ->
                        FavoriteRow(
                            fav = fav,
                            inBand = config.centerFreq?.let { c ->
                                val half = (config.sampRate ?: 0) / 2
                                fav.freqHz in (c - half)..(c + half)
                            } ?: false,
                            onClick = { vm.tuneToFavorite(fav) },
                            onDelete = { vm.removeFavorite(fav) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatDialog(
    messages: List<pl.sp8mb.owrx.session.OwrxSession.Chat>,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Czat (${messages.size})") },
        text = {
            Column {
                LazyColumn(
                    modifier = Modifier.height(260.dp).fillMaxWidth(),
                    reverseLayout = true,
                ) {
                    items(messages.reversed()) { m ->
                        Text(
                            "${m.name}: ${m.text}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Wiadomość") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
            ) { Text("Wyślij") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

@Composable
private fun FreqEntryDialog(
    currentMhz: String,
    canMoveCenter: Boolean,
    onDismiss: () -> Unit,
    onTune: (Double) -> Unit,
    onMoveCenter: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(currentMhz) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Częstotliwość (MHz)") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("MHz") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                )
                if (canMoveCenter) {
                    Text(
                        "Przesuń SDR przestawia środek odbiornika WSZYSTKIM słuchaczom.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE0A040),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (canMoveCenter) {
                    TextButton(
                        onClick = { parsed?.let(onMoveCenter) },
                        enabled = parsed != null && parsed > 0,
                    ) { Text("Przesuń SDR") }
                }
                TextButton(
                    onClick = { parsed?.let(onTune) },
                    enabled = parsed != null && parsed > 0,
                ) { Text("Strój") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun AddFavoriteDialog(
    freqLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowa zakładka — $freqLabel") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nazwa (puste = częstotliwość)") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun DrawerHandle(
    visible: Boolean,
    alignment: Alignment,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    if (!visible) return
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(alignment)
                .width(22.dp)
                .height(88.dp)
                .clickable(onClick = onClick),
            shape = if (alignment == Alignment.CenterStart)
                RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
            else
                RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
            color = Color(0xCC2A3340),
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
    }
}

@Composable
private fun DrawerPanel(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp),
        color = Color(0xF5181C22),
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            content()
        }
    }
}

@Composable
private fun FavoriteRow(fav: Favorite, inBand: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(fav.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "%.4f MHz".format(fav.freqHz / 1e6) + (fav.modulation?.let { " · ${it.uppercase()}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color.Gray,
            )
        }
        if (!inBand) {
            Text(
                "↷ profil",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFE0C040),
            )
        }
        androidx.compose.material3.IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Usuń",
                tint = Color.Gray,
            )
        }
    }
}
