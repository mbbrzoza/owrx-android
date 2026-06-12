package pl.sp8mb.owrx.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Builders for client→server JSON messages. */
object ClientCommand {

    const val HANDSHAKE = "SERVER DE CLIENT client=owrx-android type=receiver"

    fun connectionProperties(outputRate: Int = 12000, hdOutputRate: Int = 48000): String =
        buildJsonObject {
            put("type", "connectionproperties")
            putJsonObject("params") {
                put("output_rate", outputRate)
                put("hd_output_rate", hdOutputRate)
            }
        }.toString()

    fun dspStart(): String =
        buildJsonObject {
            put("type", "dspcontrol")
            put("action", "start")
        }.toString()

    fun dspParams(params: Map<String, Any?>): String =
        buildJsonObject {
            put("type", "dspcontrol")
            putJsonObject("params") {
                params.forEach { (k, v) ->
                    when (v) {
                        null -> {}
                        is Number -> put(k, JsonPrimitive(v))
                        is Boolean -> put(k, JsonPrimitive(v))
                        else -> put(k, JsonPrimitive(v.toString()))
                    }
                }
            }
        }.toString()

    fun sendMessage(text: String, name: String? = null): String =
        buildJsonObject {
            put("type", "sendmessage")
            put("text", text)
            if (name != null) put("name", name)
        }.toString()

    fun selectProfile(profileId: String, magicKey: String? = null): String =
        buildJsonObject {
            put("type", "selectprofile")
            putJsonObject("params") {
                put("profile", profileId)
                if (magicKey != null) put("key", magicKey)
            }
        }.toString()
}
