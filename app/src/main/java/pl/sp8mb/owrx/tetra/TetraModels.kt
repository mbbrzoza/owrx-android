package pl.sp8mb.owrx.tetra

data class TetraNetwork(
    val mcc: Int? = null,
    val mnc: Int? = null,
    val la: String? = null,
    val dlFreq: Long? = null,
    val ulFreq: Long? = null,
    val colorCode: Int? = null,
    val encrypted: Boolean = false,
    val crypt: Int = 0,
    val tetraTime: String? = null,
)

data class TimeslotState(
    val usage: String = "unknown", // traffic/control/common_control/reserved/unallocated/stale/unknown
    val updatedAt: Long = 0,
)

data class NeighbourCell(
    val cellId: Int,
    val carrier: Int,
    val dlfHz: Long,
    val load: Int,
    val synced: Boolean,
    val updatedAt: Long,
)

enum class SsiCategory { REAL, ADDR, ESI }

data class ActiveSsi(
    val ssi: Long,
    val encr: Int,
    val ageSec: Float,
    val sources: List<String>,
    val confirmed: Boolean,
) {
    val category: SsiCategory
        get() = when {
            encr == 2 -> SsiCategory.ESI
            confirmed -> SsiCategory.REAL
            else -> SsiCategory.ADDR
        }
}

enum class LogColor { NORMAL, GREEN, YELLOW, RED, BLUE, GRAY }

data class LogEntry(
    val at: Long,
    val text: String,
    val color: LogColor = LogColor.NORMAL,
)

data class CallState(
    val status: String = "",      // "", "Zestawienie", "Aktywne", "TX"
    val gssi: Long? = null,
    val issis: Set<Long> = emptySet(),
    val txSsi: Long? = null,
    val callId: Long? = null,
    val callType: String? = null,
    val startedAt: Long = 0,
)
