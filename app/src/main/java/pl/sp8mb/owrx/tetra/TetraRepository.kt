package pl.sp8mb.owrx.tetra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.sp8mb.owrx.session.OwrxSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State holders for the TETRA Monitor panel — a Kotlin port of the web
 * tetra_panel.js consumers (activity log, MS registrations, SDS, encrypted
 * activity, active SSI categories, timeslots, neighbours).
 */
@Singleton
class TetraRepository @Inject constructor(
    session: OwrxSession,
) {
    private val scope: CoroutineScope =
        CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private val _network = MutableStateFlow(TetraNetwork())
    val network: StateFlow<TetraNetwork> = _network.asStateFlow()

    private val _timeslots = MutableStateFlow(List(4) { TimeslotState() })
    val timeslots: StateFlow<List<TimeslotState>> = _timeslots.asStateFlow()

    private val _neighbours = MutableStateFlow<List<NeighbourCell>>(emptyList())
    val neighbours: StateFlow<List<NeighbourCell>> = _neighbours.asStateFlow()

    private val _activeSsis = MutableStateFlow<List<ActiveSsi>>(emptyList())
    val activeSsis: StateFlow<List<ActiveSsi>> = _activeSsis.asStateFlow()

    private val _call = MutableStateFlow(CallState())
    val call: StateFlow<CallState> = _call.asStateFlow()

    private val _activityLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val activityLog: StateFlow<List<LogEntry>> = _activityLog.asStateFlow()

    private val _msRegLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val msRegLog: StateFlow<List<LogEntry>> = _msRegLog.asStateFlow()

    private val _sdsLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val sdsLog: StateFlow<List<LogEntry>> = _sdsLog.asStateFlow()

    private val _encryptedLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val encryptedLog: StateFlow<List<LogEntry>> = _encryptedLog.asStateFlow()

    private val _afc = MutableStateFlow<Float?>(null)
    val afc: StateFlow<Float?> = _afc.asStateFlow()

    private val _dmoLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val dmoLog: StateFlow<List<LogEntry>> = _dmoLog.asStateFlow()

    private val _dmoStats = MutableStateFlow<String?>(null)
    val dmoStats: StateFlow<String?> = _dmoStats.asStateFlow()

    private val _burstRate = MutableStateFlow<Float?>(null)
    val burstRate: StateFlow<Float?> = _burstRate.asStateFlow()

    init {
        scope.launch {
            session.metadata.collect { obj ->
                val protocol = obj.str("protocol")
                if (protocol.equals("TETRA", ignoreCase = true)) {
                    handle(obj)
                }
            }
        }
    }

    fun now(): Long = System.currentTimeMillis()

    // exposed for unit tests
    fun handle(obj: JsonObject) {
        when (obj.str("type")) {
            "netinfo" -> onNetinfo(obj)
            "encinfo" -> _network.value = _network.value.copy(
                encrypted = obj.bool("encrypted") ?: _network.value.encrypted,
                crypt = obj.int("crypt") ?: _network.value.crypt,
            )
            "burst" -> onBurst(obj)
            "neighbours" -> onNeighbours(obj)
            "active_ssi" -> onActiveSsi(obj)
            "call_setup" -> onCallSetup(obj)
            "call_connect" -> onCallConnect(obj)
            "tx_grant" -> onTxGrant(obj)
            "call_release" -> onCallRelease(obj)
            "call_disconnect" -> activity(
                "D-Disconnect (przyczyna: ${DISCONNECT_CAUSES[obj.int("disconnect_cause")] ?: obj.int("disconnect_cause")})",
                LogColor.YELLOW,
            )
            "call_alert" -> activity("D-Alert (dzwoni) call_id=${obj.long("call_id")}", LogColor.YELLOW)
            "call_proceeding" -> activity("D-Call Proceeding call_id=${obj.long("call_id")}", LogColor.GRAY)
            "connect_ack" -> activity("D-Connect Ack call_id=${obj.long("call_id")}", LogColor.GREEN)
            "tx_state" -> activity("D-TX ${obj.str("subtype") ?: "?"} call_id=${obj.long("call_id")}", LogColor.GRAY)
            "ms_register" -> onMsRegister(obj)
            "sds" -> onSds(obj)
            "status" -> activity("Status SSI=${obj.long("ssi")}: ${obj.str("status") ?: ""}", LogColor.BLUE)
            "encrypted_activity" -> onEncryptedActivity(obj)
            "cell_change" -> activity(
                "Cell change: ${obj.str("action") ?: "?"}",
                if (obj.str("action")?.contains("fail") == true) LogColor.RED else LogColor.GRAY,
            )
            "session_reset" -> onSessionReset(obj)
            "dmo_burst" -> onDmoBurst(obj)
            "dmo_stats" -> onDmoStats(obj)
            "dmo_call_ctx" -> push(
                _dmoLog,
                LogEntry(now(), "Ctx: src=${obj.long("src")} MNI=${obj.long("mni")} ${obj.str("msg") ?: ""}", LogColor.BLUE),
                ACTIVITY_CAP,
            )
        }
    }

    private fun onDmoBurst(obj: JsonObject) {
        val txt = buildString {
            append(obj.str("sync_type") ?: "DMO")
            obj.int("tn")?.let { append(" TS$it") }
            obj.str("comm")?.let { append(" $it") }
            obj.str("msg_type")?.let { append(" $it") }
            obj.long("src")?.let { append(" src=$it") }
            obj.long("dst")?.let { append(" dst=$it") }
            val mcc = obj.int("mcc"); val mnc = obj.int("mnc")
            if (mcc != null && mnc != null) append(" ($mcc-$mnc)")
        }
        val color = if ((obj.int("enc") ?: 0) > 0) LogColor.RED else LogColor.NORMAL
        push(_dmoLog, LogEntry(now(), txt, color), ACTIVITY_CAP)
    }

    private fun onDmoStats(obj: JsonObject) {
        _dmoStats.value = "SCH/S ${obj.int("sch_s_ok") ?: 0}/${obj.int("sch_s_total") ?: 0} · " +
            "SCH/H ${obj.int("sch_h_ok") ?: 0} · PDU ${obj.int("n_pdus") ?: 0} · " +
            "TCH ${obj.int("tch_sent") ?: 0}"
    }

    private fun onNetinfo(obj: JsonObject) {
        val tt = obj["tetra_time"] as? JsonObject
        val timeStr = tt?.let {
            val secs = it.long("secs") ?: return@let null
            val h = secs / 3600 % 24
            val m = secs / 60 % 60
            val s = secs % 60
            "%02d:%02d:%02d UTC".format(h, m, s)
        }
        _network.value = TetraNetwork(
            mcc = obj.int("mcc") ?: _network.value.mcc,
            mnc = obj.int("mnc") ?: _network.value.mnc,
            la = obj.str("la") ?: _network.value.la,
            dlFreq = obj.long("dl_freq") ?: _network.value.dlFreq,
            ulFreq = obj.long("ul_freq") ?: _network.value.ulFreq,
            colorCode = obj.int("color_code") ?: _network.value.colorCode,
            encrypted = obj.bool("encrypted") ?: _network.value.encrypted,
            crypt = obj.int("crypt") ?: _network.value.crypt,
            tetraTime = timeStr ?: _network.value.tetraTime,
        )
    }

    private fun onBurst(obj: JsonObject) {
        _afc.value = obj.float("afc")
        _burstRate.value = obj.float("burst_rate")
        val ts = obj["timeslots"] as? JsonObject ?: return
        val now = now()
        _timeslots.value = List(4) { i ->
            val slot = ts["${i + 1}"] as? JsonObject
            TimeslotState(usage = slot?.str("usage") ?: "unknown", updatedAt = now)
        }
    }

    private fun onNeighbours(obj: JsonObject) {
        val cells = (obj["cells"] as? JsonArray) ?: return
        val now = now()
        _neighbours.value = cells.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            NeighbourCell(
                cellId = c.int("cell_id") ?: return@mapNotNull null,
                carrier = c.int("carrier") ?: 0,
                dlfHz = c.long("dlf") ?: 0,
                load = c.int("load") ?: 0,
                synced = c.bool("synced") ?: false,
                updatedAt = now,
            )
        }
    }

    private fun onActiveSsi(obj: JsonObject) {
        val list = (obj["ssis"] as? JsonArray) ?: return
        val parsed = list.mapNotNull { el ->
            val s = el as? JsonObject ?: return@mapNotNull null
            ActiveSsi(
                ssi = s.long("ssi") ?: return@mapNotNull null,
                encr = s.int("encr") ?: 0,
                ageSec = s.float("age") ?: 0f,
                sources = (s["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                confirmed = s.bool("confirmed") ?: false,
            )
        }
        // log appeared/disappeared like the web panel
        val prev = _activeSsis.value.associateBy { it.ssi }
        val curr = parsed.associateBy { it.ssi }
        for (ssi in curr.keys - prev.keys) {
            val cat = curr[ssi]!!.category
            if (cat != SsiCategory.ESI && !(cat == SsiCategory.ADDR && _network.value.encrypted)) {
                activity("Radio $ssi pojawiło się (${CATEGORY_LABEL[cat]})", LogColor.GREEN)
            }
        }
        for (ssi in prev.keys - curr.keys) {
            val cat = prev[ssi]!!.category
            if (cat != SsiCategory.ESI && !(cat == SsiCategory.ADDR && _network.value.encrypted)) {
                activity("Radio $ssi zniknęło", LogColor.GRAY)
            }
        }
        _activeSsis.value = parsed
    }

    private fun onCallSetup(obj: JsonObject) {
        val issi = obj.long("ssi2") ?: obj.long("calling_ssi")
        _call.value = CallState(
            status = "Zestawienie",
            gssi = obj.long("ssi"),
            issis = setOfNotNull(issi),
            callId = obj.long("call_id"),
            callType = obj.str("call_type"),
            startedAt = now(),
        )
        activity(
            "D-Setup GSSI=${obj.long("ssi")}" +
                (issi?.let { " ISSI=$it" } ?: "") +
                (obj.str("call_type")?.let { " ($it)" } ?: ""),
            LogColor.YELLOW,
        )
    }

    private fun onCallConnect(obj: JsonObject) {
        val issi = obj.long("ssi2") ?: obj.long("calling_ssi")
        val c = _call.value
        _call.value = c.copy(
            status = "Aktywne",
            issis = c.issis + setOfNotNull(issi),
            callId = obj.long("call_id") ?: c.callId,
        )
        activity("D-Connect call_id=${obj.long("call_id")}" + (issi?.let { " ISSI=$it" } ?: ""), LogColor.GREEN)
    }

    private fun onTxGrant(obj: JsonObject) {
        val issi = obj.long("ssi2") ?: obj.long("calling_ssi")
        val c = _call.value
        _call.value = c.copy(
            status = "TX",
            txSsi = issi ?: c.txSsi,
            issis = c.issis + setOfNotNull(issi),
        )
        val enc = obj.int("enc_control")
        activity(
            "D-TX Granted" + (issi?.let { " ISSI=$it" } ?: "") +
                (enc?.takeIf { it > 0 }?.let { " 🔒TEA$it" } ?: ""),
            LogColor.GREEN,
        )
    }

    private fun onCallRelease(obj: JsonObject) {
        activity(
            "D-Release call_id=${obj.long("call_id")}" + (obj.str("reason")?.let { " ($it)" } ?: ""),
            LogColor.GRAY,
        )
        _call.value = CallState()
    }

    private fun onMsRegister(obj: JsonObject) {
        val action = obj.str("action") ?: return
        val ssi = obj.long("ssi")
        val text = when (action) {
            "location_update_accept" -> {
                val type = obj.str("update_type_name") ?: "LU"
                val mac = if (obj.bool("ssi_from_mac") == true) " (SSI z MAC)" else ""
                "LU Accept SSI=$ssi$mac — $type" +
                    (obj.str("subscr_class")?.let { " klasa=$it" } ?: "")
            }
            "location_update_reject" -> "LU Reject SSI=$ssi"
            "location_update_command" -> "LU Command SSI=$ssi"
            "location_update_proceeding" -> "LU Proceeding SSI=$ssi"
            "group_attach" -> "Group Attach SSI=$ssi GSSI=${obj.long("gssi")}"
            "group_detach" -> "Group Detach SSI=$ssi GSSI=${obj.long("gssi")}"
            "attach_detach_ack" -> "Attach/Detach Ack SSI=$ssi"
            "authentication_demand" -> "Auth Demand" + (ssi?.let { " SSI=$it" } ?: "")
            "authentication_result" -> "Auth Result" + (ssi?.let { " SSI=$it" } ?: "")
            "authentication_reject" -> "Auth Reject" + (ssi?.let { " SSI=$it" } ?: "")
            "otar" -> "OTAR" + (ssi?.let { " SSI=$it" } ?: "")
            "ck_change_demand" -> "CK Change Demand"
            "ms_disable" -> "MS Disable SSI=$ssi"
            "ms_enable" -> "MS Enable SSI=$ssi"
            "mm_status" -> "MM Status (${obj.int("mm_status_code")})" + (ssi?.let { " SSI=$it" } ?: "")
            else -> "$action SSI=$ssi"
        }
        val color = when {
            action.contains("reject") || action == "ms_disable" -> LogColor.RED
            action.contains("accept") || action == "ms_enable" -> LogColor.GREEN
            else -> LogColor.NORMAL
        }
        push(_msRegLog, LogEntry(now(), text, color), MS_REG_CAP)
    }

    private fun onSds(obj: JsonObject) {
        val text = obj.str("text")
        val entry = buildString {
            append("SDS")
            obj.long("src_ssi")?.let { append(" od $it") }
            obj.long("dest_ssi")?.let { append(" do $it") }
            obj.int("status_code")?.let { append(" status=0x%04X".format(it)) }
            if (text != null) append(": \"$text\"")
            else obj.str("descr")?.let { append(" — ${it.take(120)}") }
        }
        push(_sdsLog, LogEntry(now(), entry, if (text != null) LogColor.BLUE else LogColor.NORMAL), SDS_CAP)
    }

    private fun onEncryptedActivity(obj: JsonObject) {
        val entry = buildString {
            append(obj.str("action") ?: "pdu")
            obj.long("ssi")?.takeIf { it != 0L }?.let { append(" SSI=$it") }
            obj.str("gssi")?.takeIf { it.isNotEmpty() }?.let { append(" GSSI=$it") }
            obj.int("tn")?.let { append(" TS$it") }
            obj.str("enc_mode")?.let { append(" [$it]") }
        }
        push(_encryptedLog, LogEntry(now(), entry, LogColor.RED), ENC_CAP)
    }

    private fun onSessionReset(obj: JsonObject) {
        activity(
            "Zmiana sieci: ${obj.str("old_network")} → ${obj.str("new_network")} — reset",
            LogColor.YELLOW,
        )
        _activeSsis.value = emptyList()
        _call.value = CallState()
        _neighbours.value = emptyList()
        _timeslots.value = List(4) { TimeslotState() }
        _dmoLog.value = emptyList()
    }

    private fun activity(text: String, color: LogColor) {
        push(_activityLog, LogEntry(now(), text, color), ACTIVITY_CAP)
    }

    private fun push(flow: MutableStateFlow<List<LogEntry>>, entry: LogEntry, cap: Int) {
        flow.value = (listOf(entry) + flow.value).take(cap)
    }

    companion object {
        const val ACTIVITY_CAP = 500
        const val MS_REG_CAP = 300
        const val SDS_CAP = 300
        const val ENC_CAP = 300

        val CATEGORY_LABEL = mapOf(
            SsiCategory.REAL to "🟢 Real ISSI",
            SsiCategory.ADDR to "🔵 Adres SSI",
            SsiCategory.ESI to "🔒 ESI alias",
        )

        val DISCONNECT_CAUSES = mapOf(
            0 to "nieznana", 1 to "żądanie użytkownika", 2 to "zajęty", 3 to "nieosiągalny",
            4 to "nie wspiera usługi", 5 to "brak zasobów", 6 to "odmowa sieci",
        )

        val timeFmt: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        fun fmtTime(at: Long): String = timeFmt.format(Date(at))
    }
}

// ── JsonObject helpers ──
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
