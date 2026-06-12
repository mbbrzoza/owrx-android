package pl.sp8mb.owrx.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Receiver-side radio state, built by merging partial "config" messages
 * (the server sends only changed keys after the initial full config).
 */
data class RadioConfig(
    val centerFreq: Long? = null,
    val sampRate: Int? = null,
    val fftSize: Int? = null,
    val fftCompression: String = "none",
    val audioCompression: String = "none",
    val startOffsetFreq: Int? = null,
    val startMod: String? = null,
    val sdrId: String? = null,
    val profileId: String? = null,
    val initialSquelchLevel: Float? = null,
    val allowCenterFreqChanges: Boolean = false,
    val raw: Map<String, String> = emptyMap(),
) {
    fun merge(v: JsonObject): RadioConfig {
        fun str(key: String): String? = (v[key] as? JsonPrimitive)?.contentOrNull
        fun int(key: String): Int? = (v[key] as? JsonPrimitive)?.intOrNull
        fun long(key: String): Long? = (v[key] as? JsonPrimitive)?.longOrNull
        fun bool(key: String): Boolean? = (v[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

        return copy(
            centerFreq = long("center_freq") ?: centerFreq,
            sampRate = int("samp_rate") ?: sampRate,
            fftSize = int("fft_size") ?: fftSize,
            fftCompression = str("fft_compression") ?: fftCompression,
            audioCompression = str("audio_compression") ?: audioCompression,
            startOffsetFreq = int("start_offset_freq") ?: startOffsetFreq,
            startMod = str("start_mod") ?: startMod,
            sdrId = str("sdr_id") ?: sdrId,
            profileId = str("profile_id") ?: profileId,
            initialSquelchLevel = (v["initial_squelch_level"] as? JsonPrimitive)
                ?.contentOrNull?.toFloatOrNull() ?: initialSquelchLevel,
            allowCenterFreqChanges = bool("allow_center_freq_changes") ?: allowCenterFreqChanges,
            raw = raw + v.mapValues { (it.value as? JsonPrimitive)?.contentOrNull ?: it.value.toString() },
        )
    }

    /** Combined id "sdrId|profileId" as used by selectprofile, when both known. */
    val fullProfileId: String?
        get() = if (sdrId != null && profileId != null) "$sdrId|$profileId" else null
}
