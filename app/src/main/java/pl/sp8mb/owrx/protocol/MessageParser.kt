package pl.sp8mb.owrx.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MessageParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns null for non-JSON or unparseable messages. */
    fun parse(text: String): ServerMessage? {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return null
        }
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        return try {
            when (type) {
                "config" -> ServerMessage.Config(obj["value"]!!.jsonObject)
                "profiles" -> ServerMessage.Profiles(
                    obj["value"]!!.jsonArray.map {
                        val p = it.jsonObject
                        Profile(
                            id = p["id"]!!.jsonPrimitive.content,
                            name = p["name"]!!.jsonPrimitive.content,
                        )
                    }
                )
                "receiver_details" -> ServerMessage.ReceiverDetails(obj["value"]!!.jsonObject)
                "modes" -> ServerMessage.Modes(obj["value"]!!)
                "smeter" -> ServerMessage.SMeter(obj["value"]!!.jsonPrimitive.float)
                "dial_frequencies" -> ServerMessage.DialFrequencies(obj["value"]!!)
                "bookmarks" -> ServerMessage.Bookmarks(obj["value"]!!)
                "bands" -> ServerMessage.Bands(obj["value"]!!)
                "metadata" -> ServerMessage.Metadata(obj["value"]!!.jsonObject)
                "backoff" -> ServerMessage.Backoff(obj["reason"]?.jsonPrimitive?.contentOrNull ?: "")
                "clients" -> ServerMessage.Clients(obj["value"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
                "chat_message" -> ServerMessage.ChatMessage(
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                    text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    color = obj["color"]?.jsonPrimitive?.contentOrNull ?: "white",
                )
                "log_message" -> ServerMessage.LogMessage(obj["value"]!!.jsonPrimitive.content)
                "sdr_error" -> ServerMessage.SdrError(obj["value"]!!.jsonPrimitive.content)
                "demodulator_error" -> ServerMessage.DemodulatorError(obj["value"]!!.jsonPrimitive.content)
                "secondary_config" -> ServerMessage.SecondaryConfig(obj["value"]!!.jsonObject)
                else -> ServerMessage.Unknown(type, obj)
            }
        } catch (e: Exception) {
            // tolerate schema drift: fall back to Unknown instead of crashing the session
            ServerMessage.Unknown(type, obj)
        }
    }
}
