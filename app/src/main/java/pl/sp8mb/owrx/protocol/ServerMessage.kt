package pl.sp8mb.owrx.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class Profile(
    /** combined id "sdrId|profileId" as used by selectprofile */
    val id: String,
    val name: String,
)

sealed class ServerMessage {
    /** Partial config update — fields merged into current RadioConfig. */
    data class Config(val value: JsonObject) : ServerMessage()
    data class Profiles(val profiles: List<Profile>) : ServerMessage()
    data class ReceiverDetails(val value: JsonObject) : ServerMessage()
    data class Modes(val value: JsonElement) : ServerMessage()
    data class SMeter(val level: Float) : ServerMessage()
    data class DialFrequencies(val value: JsonElement) : ServerMessage()
    data class Bookmarks(val value: JsonElement) : ServerMessage()
    data class Bands(val value: JsonElement) : ServerMessage()
    data class Metadata(val value: JsonObject) : ServerMessage()
    data class Backoff(val reason: String) : ServerMessage()
    data class Clients(val count: Int) : ServerMessage()
    data class ChatMessage(val name: String, val text: String, val color: String) : ServerMessage()
    data class LogMessage(val message: String) : ServerMessage()
    data class SdrError(val message: String) : ServerMessage()
    data class DemodulatorError(val message: String) : ServerMessage()
    data class SecondaryConfig(val value: JsonObject) : ServerMessage()
    data class Unknown(val type: String, val raw: JsonObject) : ServerMessage()
}
