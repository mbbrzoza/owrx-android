package pl.sp8mb.owrx.tetra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.sp8mb.owrx.session.AudioPipeline
import pl.sp8mb.owrx.session.OwrxSession

class TetraRepositoryTest {

    private fun repo() = TetraRepository(OwrxSession(OkHttpClient(), AudioPipeline()))

    private fun TetraRepository.feed(json: String) =
        handle(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `netinfo updates network state with tetra time`() {
        val r = repo()
        r.feed(
            """{"protocol":"TETRA","type":"netinfo","mcc":260,"mnc":10,"dl_freq":428065000,
                "ul_freq":418065000,"color_code":1,"encrypted":true,"crypt":2,"la":"1234",
                "tetra_time":{"secs":45296,"offset_min":120,"year":2026}}"""
        )
        val n = r.network.value
        assertEquals(260, n.mcc)
        assertEquals(10, n.mnc)
        assertEquals(428_065_000L, n.dlFreq)
        assertEquals(2, n.crypt)
        assertTrue(n.encrypted)
        assertEquals("12:34:56 UTC", n.tetraTime)
    }

    @Test
    fun `call lifecycle setup - tx - release`() {
        val r = repo()
        r.feed("""{"protocol":"TETRA","type":"call_setup","ssi":91,"ssi2":7383100,"call_id":5,"call_type":"group"}""")
        assertEquals("Zestawienie", r.call.value.status)
        assertEquals(91L, r.call.value.gssi)
        assertTrue(7383100L in r.call.value.issis)

        r.feed("""{"protocol":"TETRA","type":"tx_grant","ssi":91,"ssi2":7383101,"call_id":5,"enc_control":0}""")
        assertEquals("TX", r.call.value.status)
        assertEquals(7383101L, r.call.value.txSsi)
        assertTrue(r.call.value.issis.containsAll(setOf(7383100L, 7383101L)))

        r.feed("""{"protocol":"TETRA","type":"call_release","ssi":91,"call_id":5,"reason":"normal"}""")
        assertEquals("", r.call.value.status)
        assertTrue(r.activityLog.value.size >= 3)
    }

    @Test
    fun `active_ssi categories and appear-disappear log`() {
        val r = repo()
        r.feed(
            """{"protocol":"TETRA","type":"active_ssi","ssis":[
                {"ssi":7383100,"encr":1,"age":1.0,"sources":["calling_ssi"],"confirmed":true},
                {"ssi":13146989,"encr":0,"age":2.0,"sources":["resource_addr"],"confirmed":false},
                {"ssi":9999999,"encr":2,"age":3.0,"sources":["resource_addr"],"confirmed":false}
            ],"total":3}"""
        )
        val list = r.activeSsis.value
        assertEquals(3, list.size)
        assertEquals(SsiCategory.REAL, list.first { it.ssi == 7383100L }.category)
        assertEquals(SsiCategory.ADDR, list.first { it.ssi == 13146989L }.category)
        assertEquals(SsiCategory.ESI, list.first { it.ssi == 9999999L }.category)

        // ESI appearance must NOT be logged; real+addr on clear network are
        val appearLogs = r.activityLog.value.filter { it.text.contains("pojawiło") }
        assertEquals(2, appearLogs.size)

        // disappearance
        r.feed("""{"protocol":"TETRA","type":"active_ssi","ssis":[],"total":0}""")
        val disappearLogs = r.activityLog.value.filter { it.text.contains("zniknęło") }
        assertEquals(2, disappearLogs.size)
    }

    @Test
    fun `ms_register and sds land in their logs`() {
        val r = repo()
        r.feed(
            """{"protocol":"TETRA","type":"ms_register","action":"location_update_accept",
                "ssi":7383100,"ssi_from_mac":true,"update_type":3,
                "update_type_name":"Roaming location updating","addr_type":2}"""
        )
        assertEquals(1, r.msRegLog.value.size)
        assertTrue(r.msRegLog.value[0].text.contains("7383100"))
        assertTrue(r.msRegLog.value[0].text.contains("SSI z MAC"))
        assertEquals(LogColor.GREEN, r.msRegLog.value[0].color)

        r.feed("""{"protocol":"TETRA","type":"sds","text":"TEST 123","src_ssi":7383100,"dest_ssi":91}""")
        assertEquals(1, r.sdsLog.value.size)
        assertTrue(r.sdsLog.value[0].text.contains("TEST 123"))
        assertTrue(r.sdsLog.value[0].text.contains("7383100"))
    }

    @Test
    fun `burst updates timeslots and session_reset clears state`() {
        val r = repo()
        r.feed(
            """{"protocol":"TETRA","type":"burst","afc":-120.5,"burst_rate":42.0,
                "timeslots":{"1":{"usage":"control","age":0.1},"2":{"usage":"traffic","age":0.2},
                             "3":{"usage":"unallocated","age":0.3},"4":{"usage":"unknown","age":null}}}"""
        )
        assertEquals(listOf("control", "traffic", "unallocated", "unknown"), r.timeslots.value.map { it.usage })
        assertEquals(-120.5f, r.afc.value)

        r.feed("""{"protocol":"TETRA","type":"call_setup","ssi":91,"call_id":1}""")
        r.feed("""{"protocol":"TETRA","type":"session_reset","old_network":"260-10","new_network":"901-9999"}""")
        assertEquals("", r.call.value.status)
        assertEquals(List(4) { "unknown" }, r.timeslots.value.map { it.usage })
    }

    @Test
    fun `encrypted_activity log formatting`() {
        val r = repo()
        r.feed(
            """{"protocol":"TETRA","type":"encrypted_activity","action":"tx_grant","la":"1234",
                "ssi":13146989,"gssi":"15117191","tn":2,"call_id":7,"enc_mode":"TEA1","source_event":"tx_grant"}"""
        )
        assertEquals(1, r.encryptedLog.value.size)
        val e = r.encryptedLog.value[0]
        assertTrue(e.text.contains("13146989"))
        assertTrue(e.text.contains("TEA1"))
        assertTrue(e.text.contains("TS2"))
        assertEquals(LogColor.RED, e.color)
    }
}
