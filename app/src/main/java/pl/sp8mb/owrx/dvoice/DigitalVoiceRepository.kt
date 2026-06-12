package pl.sp8mb.owrx.dvoice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.sp8mb.owrx.session.OwrxSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Who's transmitting" for digital voice modes (DMR/YSF/D-STAR/NXDN), built
 * from the same metadata stream the TETRA panel consumes. Cleared after a few
 * seconds of no update so a stale call doesn't linger.
 */
@Singleton
class DigitalVoiceRepository @Inject constructor(
    session: OwrxSession,
) {
    data class Activity(
        val protocol: String,
        val line1: String,        // who / source
        val line2: String? = null, // target / reflector / extra
        val slot: Int? = null,
        val at: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _active = MutableStateFlow<Activity?>(null)
    val active: StateFlow<Activity?> = _active.asStateFlow()

    init {
        scope.launch {
            session.metadata.collect { handle(it) }
        }
        scope.launch {
            while (true) {
                delay(1000)
                val a = _active.value ?: continue
                if (System.currentTimeMillis() - a.at > TTL_MS) _active.value = null
            }
        }
    }

    fun handle(obj: JsonObject) {
        val protocol = obj.str("protocol")?.uppercase() ?: return
        val now = System.currentTimeMillis()
        when (protocol) {
            "DMR" -> {
                val slot = obj.int("slot") ?: return
                val src = obj.callsign() ?: obj.str("talkeralias") ?: obj.str("source")
                if (src.isNullOrEmpty()) {
                    // slot inactive (no source) → if it was the active slot, clear
                    if (_active.value?.slot == slot) _active.value = null
                    return
                }
                _active.value = Activity("DMR", src, obj.str("target")?.let { "→ $it" }, slot, now)
            }
            "YSF" -> {
                val mode = obj.str("mode")
                val src = obj.str("source")
                if (mode.isNullOrEmpty() || src.isNullOrEmpty()) {
                    _active.value = null; return
                }
                _active.value = Activity("YSF", src, obj.str("up") ?: obj.str("down"), null, now)
            }
            "DSTAR" -> {
                if (obj.str("sync") != "voice") return
                val src = obj.str("ourcall")
                if (src.isNullOrEmpty()) {
                    _active.value = null; return
                }
                val dst = obj.str("yourcall")
                _active.value = Activity("D-STAR", src, dst?.let { "→ $it" }, null, now)
            }
            "NXDN" -> {
                val src = obj.str("source") ?: return
                _active.value = Activity("NXDN", src, obj.str("target")?.let { "→ $it" }, null, now)
            }
        }
    }

    companion object {
        const val TTL_MS = 4000L
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.callsign(): String? =
    (this["additional"] as? JsonObject)?.let { (it["callsign"] as? JsonPrimitive)?.content?.takeIf { c -> c.isNotEmpty() } }
