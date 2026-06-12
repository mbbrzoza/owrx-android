package pl.sp8mb.owrx.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.sp8mb.owrx.session.RadioConfig

class MessageParserTest {

    private fun fixtureLines(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().readLines().filter { it.isNotBlank() }

    @Test
    fun `all captured messages parse without crash`() {
        val lines = fixtureLines("messages.jsonl")
        assertTrue(lines.size > 10)
        for (line in lines) {
            assertNotNull("failed: ${line.take(80)}", MessageParser.parse(line))
        }
    }

    @Test
    fun `partial configs merge into full radio state`() {
        var config = RadioConfig()
        for (line in fixtureLines("messages.jsonl")) {
            val msg = MessageParser.parse(line)
            if (msg is ServerMessage.Config) config = config.merge(msg.value)
        }
        // values from the live capture (airband profile on rtlsdr)
        assertEquals(136_000_000L, config.centerFreq)
        assertEquals(2_400_000, config.sampRate)
        assertEquals(4092, config.fftSize)
        assertEquals("adpcm", config.fftCompression)
        assertEquals("adpcm", config.audioCompression)
        assertEquals("am", config.startMod)
        assertEquals(425_000, config.startOffsetFreq)
        assertEquals("rtlsdr", config.sdrId)
        assertNotNull(config.profileId)
        assertEquals("rtlsdr|${config.profileId}", config.fullProfileId)
    }

    @Test
    fun `profiles list parses with combined ids`() {
        val profilesLine = fixtureLines("messages.jsonl").first { it.contains("\"profiles\"") }
        val msg = MessageParser.parse(profilesLine)
        assertTrue(msg is ServerMessage.Profiles)
        val profiles = (msg as ServerMessage.Profiles).profiles
        assertTrue(profiles.isNotEmpty())
        for (p in profiles) {
            assertTrue("profile id '${p.id}' must be sdr|profile", p.id.contains("|"))
            assertTrue(p.name.isNotBlank())
        }
    }

    @Test
    fun `client commands have expected shape`() {
        assertEquals("SERVER DE CLIENT client=owrx-android type=receiver", ClientCommand.HANDSHAKE)
        assertTrue(ClientCommand.dspStart().contains("\"action\":\"start\""))
        val params = ClientCommand.dspParams(mapOf("offset_freq" to 425000, "mod" to "am", "squelch_level" to -80.0f))
        assertTrue(params.contains("\"offset_freq\":425000"))
        assertTrue(params.contains("\"mod\":\"am\""))
        val sel = ClientCommand.selectProfile("rtlsdr|airband")
        assertTrue(sel.contains("\"profile\":\"rtlsdr|airband\""))
    }
}
