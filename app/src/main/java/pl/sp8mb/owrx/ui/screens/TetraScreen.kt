package pl.sp8mb.owrx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import pl.sp8mb.owrx.tetra.LogColor
import pl.sp8mb.owrx.tetra.LogEntry
import pl.sp8mb.owrx.tetra.SsiCategory
import pl.sp8mb.owrx.tetra.TetraRepository
import javax.inject.Inject

@HiltViewModel
class TetraViewModel @Inject constructor(val repo: TetraRepository) : ViewModel()

@Composable
fun TetraScreen(vm: TetraViewModel = hiltViewModel()) {
    val network by vm.repo.network.collectAsState()
    val timeslots by vm.repo.timeslots.collectAsState()
    val neighbours by vm.repo.neighbours.collectAsState()
    val activeSsis by vm.repo.activeSsis.collectAsState()
    val call by vm.repo.call.collectAsState()
    val activity by vm.repo.activityLog.collectAsState()
    val msReg by vm.repo.msRegLog.collectAsState()
    val sds by vm.repo.sdsLog.collectAsState()
    val enc by vm.repo.encryptedLog.collectAsState()
    val afc by vm.repo.afc.collectAsState()
    val burstRate by vm.repo.burstRate.collectAsState()

    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── network header ──
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Sieć: ${network.mcc ?: "—"}-${network.mnc ?: "—"}" +
                            (network.la?.let { " LA=$it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        if (network.crypt == 2) "🔒 Szyfrowana" else if (network.crypt == 1) "🔓 Jawna" else "Szyfr.: ?",
                        color = if (network.crypt == 2) Color(0xFFFF6060) else Color(0xFF7CCB7C),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "DL: ${network.dlFreq?.let { "%.4f MHz".format(it / 1e6) } ?: "—"}" +
                        (network.tetraTime?.let { "  ⏱ $it" } ?: "") +
                        (afc?.let { "  AFC %.0f Hz".format(it) } ?: "") +
                        (burstRate?.let { "  %.0f burst/s".format(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                // timeslots
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Szczeliny:", style = MaterialTheme.typography.bodySmall)
                    timeslots.forEachIndexed { i, ts ->
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(timeslotColor(ts.usage)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                        }
                    }
                    if (call.status.isNotEmpty()) {
                        Text(
                            "  ${call.status}" +
                                (call.gssi?.let { " G=$it" } ?: "") +
                                (call.txSsi?.let { " TX=$it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (call.status == "TX") Color(0xFF7CCB7C) else Color(0xFFE0C040),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (neighbours.isNotEmpty()) {
                    Text(
                        "Sąsiedzi: " + neighbours.take(4).joinToString(" ") {
                            "c${it.cellId}@%.3f".format(it.dlfHz / 1e6)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray,
                    )
                }
            }
        }

        // ── tabs ──
        val tabs = listOf(
            "Aktywność (${activity.size})",
            "SSI (${activeSsis.size})",
            "MS Rej. (${msReg.size})",
            "SDS (${sds.size})",
            "🔒 (${enc.size})",
        )
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) })
            }
        }

        when (tab) {
            0 -> LogList(activity)
            1 -> SsiList(activeSsis.map { it }, networkEncrypted = network.encrypted)
            2 -> LogList(msReg)
            3 -> LogList(sds)
            4 -> LogList(enc)
        }
    }
}

@Composable
private fun LogList(entries: List<LogEntry>) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(entries) { e ->
            Row {
                Text(
                    TetraRepository.fmtTime(e.at) + " ",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Gray,
                )
                Text(
                    e.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (e.color) {
                        LogColor.GREEN -> Color(0xFF7CCB7C)
                        LogColor.YELLOW -> Color(0xFFE0C040)
                        LogColor.RED -> Color(0xFFFF6060)
                        LogColor.BLUE -> Color(0xFF64B5F6)
                        LogColor.GRAY -> Color.Gray
                        LogColor.NORMAL -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun SsiList(ssis: List<pl.sp8mb.owrx.tetra.ActiveSsi>, networkEncrypted: Boolean) {
    val visible = ssis.filter {
        when (it.category) {
            SsiCategory.REAL -> true
            SsiCategory.ADDR -> !networkEncrypted
            SsiCategory.ESI -> false
        }
    }.sortedBy { it.ageSec }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(visible, key = { it.ssi }) { s ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${TetraRepository.CATEGORY_LABEL[s.category]}  ${s.ssi}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "${s.ageSec.toInt()}s · ${s.sources.joinToString(",")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }
    }
}

private fun timeslotColor(usage: String): Color = when (usage) {
    "traffic" -> Color(0xFF40C040)
    "control" -> Color(0xFF4080E0)
    "common_control" -> Color(0xFF40C0C0)
    "reserved" -> Color(0xFFE0C040)
    "unallocated" -> Color(0xFF606060)
    "stale" -> Color(0xFF404040)
    else -> Color(0xFF303030)
}
