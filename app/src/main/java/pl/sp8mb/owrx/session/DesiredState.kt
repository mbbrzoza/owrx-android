package pl.sp8mb.owrx.session

/**
 * What the user wants the receiver to do. Source of truth replayed to the
 * server after every (re)connect, so a dropped LTE link resumes seamlessly.
 */
@kotlinx.serialization.Serializable
data class DesiredState(
    val profileId: String? = null,
    val offsetFreq: Int? = null,
    val mod: String? = null,
    val squelchLevel: Float? = null,
    val lowCut: Int? = null,
    val highCut: Int? = null,
) {
    fun dspParams(): Map<String, Any?> = buildMap {
        offsetFreq?.let { put("offset_freq", it) }
        mod?.let { put("mod", it) }
        squelchLevel?.let { put("squelch_level", it) }
        lowCut?.let { put("low_cut", it) }
        highCut?.let { put("high_cut", it) }
    }
}
