package com.rideinsight.fit

// [파일 역할] 직접 쓴 FIT 디코더를 python-fitparse(MIT)의 디코딩 결과와 대조하는 테스트. 기대값
// JSON(src/test/resources/fit/expected/)은 ml/의 fit_expected.py가 같은 샘플 FIT 파일을 fitparse로 읽어
// 만든다.
// [왜 중요한가] 직접 구현한 바이너리 디코더의 필드 번호·정의 크기 실수는 작은 손 예제로는 놓치기 쉽다. 이
// 대조가 실제로 버그 2개를 잡았다: session의 threshold_power 필드 번호(112 → 45), 개발자 필드 정의를 필드당
// 2바이트로 읽어 Wahoo ELEMNT 파일이 어긋난 문제(docs/ai-verification.md FIT 디코더).

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

    // fitparse는 제조사·파일 종류를 이름으로 내보내므로, 비교에 쓰는 번호 → 이름 표.
    private val manufacturerNames = mapOf(1L to "garmin", 32L to "wahoo_fitness", 294L to "coros")
    private val fileTypeNames = mapOf(4L to "activity")

    // 정상적으로 읽혀야 하는 샘플 6개: Garmin Edge 810(파워미터 Vector)·500·820, Wahoo ELEMNT, 실내 트레이너,
    // Garmin fenix 5 러닝. 손상 파일과 Coros 파일은 아래 별도 테스트에서 다룬다.
    private val decodable = listOf(
        "Edge810-Vector-2013-08-16-15-35-10",
        "garmin-edge-500-activity",
        "garmin-edge-820-bike",
        "2019-02-17-062644-ELEMNT-297E-195-0",
        "sample-activity-indoor-trainer",
        "garmin-fenix-5-run",
    )

    // 모든 정상 샘플이 예외 없이 디코딩되는지. 실패한 파일을 모두 모아 한 번에 보여 준다.
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

    // record 시계열 대조: 개수와 CRC, 그리고 모든 record의 timestamp와 파워·심박·케이던스·거리·속도·고도를
    // 1e-6 안에서 비교한다. fitparse에 값이 없으면 우리도 null이어야 한다(없는 값을 지어내지 않는지도 확인).
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

    // file_id(종류·제조사·일련번호·만든 시각)와 세션 요약(종목, 시간·거리, TSS·IF, 평균·최대·NP 파워,
    // threshold_power, 상승 고도, 시작 시각)을 fitparse와 비교한다. threshold_power 필드 번호 오류를 잡은
    // 테스트가 이것이다.
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

    // 배율이 적용된 값 비교: fitparse 값이 없으면 null, 있으면 1e-6 안에서 같아야 한다.
    private fun assertScaled(e: JsonNode, a: Double?, what: String) {
        if (e.isNull || e.isMissingNode) {
            assertEquals(null, a, "$what should be absent")
        } else {
            assertNotNull(a, "$what missing")
            assertTrue(abs(e.asDouble() - a) < 1e-6, "$what: expected ${e.asDouble()} got $a")
        }
    }

    // 손상 파일(헤더 손상, 중간에 끊김), 빈 입력, 텍스트 입력은 FitDecodeException만 내야 한다. 다른 예외
    // 형이 나오면 API에서 400이 아니라 500이 되기 때문이다.
    @Test
    fun `corrupt files fail with FitDecodeException only`() {
        assertFailsWith<FitDecodeException> { FitActivity.decode(bytes("activity-settings-corruptheader")) }
        assertFailsWith<FitDecodeException> { FitActivity.decode(bytes("activity-unexpected-eof")) }
        assertFailsWith<FitDecodeException> { FitActivity.decode(ByteArray(0)) }
        assertFailsWith<FitDecodeException> { FitActivity.decode("not a fit file at all, just text".toByteArray()) }
    }

    // fitparse도 거절하는 Coros 파일(개발자 필드 정렬 오류)은 디코딩되거나 FitDecodeException으로 깔끔하게
    // 거절되면 된다. 다른 예외 형이면 실패다.
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

    // 정상 파일의 CRC는 맞고, 가운데 바이트 하나를 바꾸면 CRC가 틀리거나(crcValid=false) 구조 오류 예외가
    // 난다. 어느 쪽이든 변조를 놓치지 않는다.
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

    // 종목 판별: fenix 5 러닝 파일은 RUNNING, Edge 820 파일은 CYCLING. 업로드 거절(422)의 근거가 되는 값이다.
    @Test
    fun `running files are identified by sport`() {
        val run = FitActivity.decode(bytes("garmin-fenix-5-run"))
        assertEquals(FitProfile.Sport.RUNNING, run.sport)
        val ride = FitActivity.decode(bytes("garmin-edge-820-bike"))
        assertEquals(FitProfile.Sport.CYCLING, ride.sport)
    }
}
