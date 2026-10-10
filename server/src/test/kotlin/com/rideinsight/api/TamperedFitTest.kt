package com.rideinsight.api

import com.rideinsight.fit.FitActivity
import com.rideinsight.fit.FitWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/experiments.md 5단계 H8: tampered FIT files get the expected status, never 500. */
class TamperedFitTest : ApiTestBase() {
    private fun code(r: Response) = "${r.status} ${r.body?.path("error")?.asString(null)}"

    @Test
    fun `a generated ride decodes and uploads`() {
        val bytes = FitWriter.ride(120).build()
        val act = FitActivity.decode(bytes)
        assertEquals(120, act.records.size)
        assertTrue(act.crcValid)
        val id = createAthlete()
        val r = uploadBytes(id, "generated.fit", bytes)
        assertEquals(201, r.status, r.body.toString())
        assertEquals(120, r.body!!.path("sampleCount").asInt())
        assertTrue(r.body!!.path("crcValid").asBoolean())
    }

    @Test
    fun `tampered files never produce a 500`() {
        val id = createAthlete()
        val good = FitWriter.ride(60)
        val cases = linkedMapOf(
            "1-declared-size-too-large" to good.build(declaredDataSize = 100_000),
            "2-declared-size-too-small" to good.build(declaredDataSize = 10),
            "3-broken-crc" to good.build(breakCrc = true),
            "4-data-before-definition" to FitWriter().raw(byteArrayOf(0x03, 0x01, 0x02)).build(),
            "5-definition-with-255-fields-truncated" to FitWriter().raw(byteArrayOf(0x40, 0x00, 0x00, 0x14, 0x00, 0xFF.toByte(), 0x01, 0x02)).build(),
            "6-timestamps-backwards" to FitWriter.ride(60, backwards = true, startUnix = 1_600_100_000L).build(),
        )
        val expected = mapOf(
            "1-declared-size-too-large" to "400 invalid_fit",
            "2-declared-size-too-small" to "400 invalid_fit",
            "3-broken-crc" to "201 null",
            "4-data-before-definition" to "400 invalid_fit",
            "5-definition-with-255-fields-truncated" to "400 invalid_fit",
            "6-timestamps-backwards" to "400 invalid_activity",
        )
        val results = LinkedHashMap<String, String>()
        for ((name, bytes) in cases) {
            val r = uploadBytes(id, "$name.fit", bytes)
            results[name] = code(r)
            if (name == "3-broken-crc" && r.status == 201) assertFalse(r.body!!.path("crcValid").asBoolean(), "crcValid must be false")
        }
        // 7: one byte over the 10 MB multipart limit. Tomcat answers 413 and closes the connection
        // without swallowing the rest of the body, so a client that is still sending may see a reset
        // instead of the status line; both outcomes are the intended rejection.
        val big = ByteArray(10 * 1024 * 1024 + 1)
        results["7-over-10mb"] = try {
            code(uploadBytes(id, "big.fit", big))
        } catch (e: org.springframework.web.client.ResourceAccessException) {
            "413 (connection closed by server: ${e.message?.take(60)})"
        }
        println("TAMPERED_FIT_RESULTS " + results.entries.joinToString(" | ") { "${it.key}=${it.value}" })
        for ((name, exp) in expected) assertEquals(exp, results[name], name)
        assertTrue(results["7-over-10mb"]!!.startsWith("413"), results["7-over-10mb"])
        assertTrue(results.values.none { it.startsWith("500") }, "no 500s: $results")
    }
}
