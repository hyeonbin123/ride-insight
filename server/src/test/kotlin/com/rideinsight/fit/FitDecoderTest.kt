package com.rideinsight.fit

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Compares the Kotlin decoder with python-fitparse (MIT) on the sample files under
 * src/test/resources/fit. The reference JSON is produced by `python -m ride_insight_ml.fit_expected`.
 */
class FitDecoderTest {
    private val mapper = JsonMapper.builder().build()
    private val dir = File("src/test/resources/fit")

    private fun expected(name: String): JsonNode = mapper.readTree(File(dir, "expected/$name.json"))

    private fun bytes(name: String): ByteArray = File(dir, "$name.fit").readBytes()

    private val manufacturerNames = mapOf(1L to "garmin", 32L to "wahoo_fitness", 294L to "coros")
    private val fileTypeNames = mapOf(4L to "activity")

    private val decodable = listOf(
        "Edge810-Vector-2013-08-16-15-35-10",
        "garmin-edge-500-activity",
        "garmin-edge-820-bike",
        "2019-02-17-062644-ELEMNT-297E-195-0",
        "sample-activity-indoor-trainer",
        "garmin-fenix-5-run",
    )

    @Test
    fun `every decodable sample decodes`() {
        val failures = decodable.mapNotNull { name ->
            try {
                FitActivity.decode(bytes(name))
                null
            } catch (e: FitDecodeException) {
                "$name: ${e.message}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `record series match python-fitparse on every decodable sample`() {
        for (name in decodable) {
            val exp = expected(name)
            val act = try {
                FitActivity.decode(bytes(name))
            } catch (e: FitDecodeException) {
                throw AssertionError("$name failed to decode: ${e.message}", e)
            }
            val recs = exp.path("records")
            assertEquals(exp.path("record_count").asInt(), act.records.size, "$name record count")
            assertTrue(act.crcValid, "$name crc")
            val fields = listOf(
                "power" to { r: FitRecordSample -> r.power?.toDouble() },
                "heart_rate" to { r: FitRecordSample -> r.heartRate?.toDouble() },
                "cadence" to { r: FitRecordSample -> r.cadence?.toDouble() },
                "distance" to { r: FitRecordSample -> r.distanceMeters },
                "speed" to { r: FitRecordSample -> r.speedMetersPerSecond },
                "altitude" to { r: FitRecordSample -> r.altitudeMeters },
            )
            for ((i, r) in act.records.withIndex()) {
                assertEquals(recs.path("timestamp").get(i).asLong(), r.timestampUnix, "$name timestamp[$i]")
                for ((field, get) in fields) {
                    val e = recs.path(field).get(i)
                    val a = get(r)
                    if (e == null || e.isNull) {
                        assertEquals(null, a, "$name $field[$i] should be absent")
                    } else {
                        assertNotNull(a, "$name $field[$i] missing (expected ${e.asDouble()})")
                        assertTrue(abs(e.asDouble() - a) < 1e-6, "$name $field[$i]: expected ${e.asDouble()} got $a")
                    }
                }
            }
        }
    }

    @Test
    fun `file id and session summaries match python-fitparse`() {
        for (name in decodable) {
            val exp = expected(name)
            val act = FitActivity.decode(bytes(name))
            val fid = exp.path("file_id")
            if (!fid.isMissingNode) {
                assertEquals(fid.path("type").asString(), fileTypeNames[act.fileId?.type], "$name file type")
                val manufacturer = fid.path("manufacturer")
                if (manufacturer.isNumber) {
                    assertEquals(manufacturer.asLong(), act.fileId?.manufacturer, "$name manufacturer")
                } else {
                    assertEquals(manufacturer.asString(), manufacturerNames[act.fileId?.manufacturer], "$name manufacturer")
                }
                if (!fid.path("serial_number").isNull) assertEquals(fid.path("serial_number").asLong(), act.fileId?.serialNumber, "$name serial")
                if (!fid.path("time_created").isNull) assertEquals(fid.path("time_created").asLong(), act.fileId?.timeCreatedUnix, "$name time_created")
            }
            val sessions = exp.path("sessions")
            assertEquals(sessions.size(), act.sessions.size, "$name session count")
            for ((i, s) in act.sessions.withIndex()) {
                val e = sessions.get(i)
                assertEquals(e.path("sport").asString(null), FitProfile.sportName(s.sport), "$name sport")
                assertScaled(e.path("total_elapsed_time"), s.totalElapsedSecs, "$name total_elapsed_time")
                assertScaled(e.path("total_timer_time"), s.totalTimerSecs, "$name total_timer_time")
                assertScaled(e.path("total_distance"), s.totalDistanceMeters, "$name total_distance")
                assertScaled(e.path("training_stress_score"), s.trainingStressScore, "$name tss")
                assertScaled(e.path("intensity_factor"), s.intensityFactor, "$name if")
                assertEquals(e.path("avg_power").asLong(-1).takeIf { it >= 0 }?.toInt(), s.avgPower, "$name avg_power")
                assertEquals(e.path("max_power").asLong(-1).takeIf { it >= 0 }?.toInt(), s.maxPower, "$name max_power")
                assertEquals(e.path("normalized_power").asLong(-1).takeIf { it >= 0 }?.toInt(), s.normalizedPower, "$name np")
                assertEquals(e.path("threshold_power").asLong(-1).takeIf { it >= 0 }?.toInt(), s.thresholdPower, "$name ftp")
                assertEquals(e.path("total_ascent").asLong(-1).takeIf { it >= 0 }?.toInt(), s.totalAscentMeters, "$name ascent")
                if (!e.path("start_time").isNull) assertEquals(e.path("start_time").asLong(), s.startTimeUnix, "$name start_time")
            }
        }
    }

    private fun assertScaled(e: JsonNode, a: Double?, what: String) {
        if (e.isNull || e.isMissingNode) {
            assertEquals(null, a, "$what should be absent")
        } else {
            assertNotNull(a, "$what missing")
            assertTrue(abs(e.asDouble() - a) < 1e-6, "$what: expected ${e.asDouble()} got $a")
        }
    }

    @Test
    fun `corrupt files fail with FitDecodeException only`() {
        assertFailsWith<FitDecodeException> { FitActivity.decode(bytes("activity-settings-corruptheader")) }
        assertFailsWith<FitDecodeException> { FitActivity.decode(bytes("activity-unexpected-eof")) }
        assertFailsWith<FitDecodeException> { FitActivity.decode(ByteArray(0)) }
        assertFailsWith<FitDecodeException> { FitActivity.decode("not a fit file at all, just text".toByteArray()) }
    }

    @Test
    fun `misaligned developer fields are either decoded or rejected cleanly`() {
        // python-fitparse raises FitParseError on this Coros file; we accept either outcome but no
        // other exception type.
        try {
            val act = FitActivity.decode(bytes("coros-pace-2-cycling-misaligned-fields"))
            assertTrue(act.records.isNotEmpty())
        } catch (e: FitDecodeException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun `crc of the header and data is verified`() {
        val good = bytes("garmin-edge-820-bike")
        assertTrue(FitActivity.decode(good).crcValid)
        val tampered = good.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()
        // Flipping a data byte either breaks the structure (exception) or only the CRC.
        try {
            assertTrue(!FitActivity.decode(tampered).crcValid)
        } catch (_: FitDecodeException) {
            // acceptable
        }
    }

    @Test
    fun `running files are identified by sport`() {
        val run = FitActivity.decode(bytes("garmin-fenix-5-run"))
        assertEquals(FitProfile.Sport.RUNNING, run.sport)
        val ride = FitActivity.decode(bytes("garmin-edge-820-bike"))
        assertEquals(FitProfile.Sport.CYCLING, ride.sport)
    }
}
