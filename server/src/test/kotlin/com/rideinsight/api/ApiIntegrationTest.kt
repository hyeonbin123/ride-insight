package com.rideinsight.api

// [파일 역할] docs/api.md 2단계 완료 기준을 실제 HTTP + PostgreSQL 컨테이너로 확인하는 통합 테스트 묶음: 입력
// 검증, 샘플 FIT 업로드·거절·중복, 업로드 지표와 순수 함수 계산의 일치, FTP 변경 재계산과 훈련 부하, 라우팅
// 오류와 보안 헤더, health, 파워 없는 파일.
// [왜 중요한가] 단위 테스트로는 못 잡는 실수를 이 층에서 잡았다: PgJDBC의 float4 읽기 오류(선수 조회가 전부
// 500), multipart 테스트 클라이언트 의존성 문제, 파워가 없는 파일로 재계산을 시험한 테스트 가정 오류. ZAP이
// 찾은 404→500 버그도 여기서 회귀를 막는다(docs/ai-verification.md 2·5단계).

import com.rideinsight.fit.FitActivity
import com.rideinsight.fit.FitWriter
import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.PowerMetrics
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.metrics.RideSamples
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.JsonNode

/** PostgreSQL 컨테이너를 상대로 docs/api.md의 내용을 처음부터 끝까지(end-to-end) 확인한다. */
class ApiIntegrationTest : ApiTestBase() {
    // 선수 생성 검증: 빈 이름·FTP 20 W·성별 Q·JSON이 아닌 본문은 400, 정상 생성 후 조회하면 현재 FTP 315,
    // 없는 id는 404, UUID가 아닌 id는 500이 아니라 400.
    @Test
    fun `athlete validation and lookup`() {
        assertEquals(400, post("/api/athletes", """{"name":""}""").status)
        assertEquals(400, post("/api/athletes", """{"name":"x","ftp":20}""").status)
        assertEquals(400, post("/api/athletes", """{"name":"x","gender":"Q"}""").status)
        assertEquals(400, post("/api/athletes", """not json""").status)
        val id = createAthlete()
        val got = get("/api/athletes/$id")
        assertEquals(200, got.status)
        assertEquals(315, got.body!!.path("currentThreshold").path("ftpWatts").asInt())
        assertEquals(404, get("/api/athletes/00000000-0000-0000-0000-000000000000").status)
        assertEquals(400, get("/api/athletes/not-a-uuid").status)
    }

    // 체중 검증: DB의 check(20 < weight_kg < 300, real 컬럼) 밖의 값은 DB까지 가지 않고 400 validation이어야
    // 한다. 처음에는 체중에 검증이 없어 이 값들이 check 위반 예외로 500이 됐다. 20.0000001·299.99999는 그
    // 자체로는 범위 안이지만 real(4바이트 부동소수)로 저장하면 20.0·300.0으로 반올림돼 check에 걸리는 값이라,
    // 검증도 저장되는 값 기준이어야 한다는 것을 확인한다. 범위 안의 값은 201로 저장되고 그대로 돌아온다.
    @Test
    fun `athlete weight outside the stored range is a 400 validation error, not a 500`() {
        for (w in listOf("20", "300", "5", "1000", "-1", "20.0000001", "299.99999")) {
            val r = post("/api/athletes", """{"name":"w","weightKg":$w}""")
            assertEquals(400, r.status, "weightKg=$w: ${r.body}")
            assertEquals("validation", r.body!!.path("error").asString(), "weightKg=$w")
        }
        // 4바이트 float에 담기지 않을 만큼 큰 수도 거절돼야 한다(500이 되면 안 된다).
        assertEquals(400, post("/api/athletes", """{"name":"w","weightKg":1e40}""").status)
        for (w in listOf("20.5", "72.5", "299.5")) {
            val r = post("/api/athletes", """{"name":"w","weightKg":$w}""")
            assertEquals(201, r.status, "weightKg=$w: ${r.body}")
            assertEquals(w.toDouble(), r.body!!.path("weightKg").asDouble(), 1e-3)
        }
    }

    // 완료 기준 2: 샘플 중 사이클링 5개 201, 러닝 422, 손상 2개 400 invalid_fit, Coros(개발자 필드 정렬 오류,
    // python-fitparse도 거절)는 201 또는 400(500만 아니면 됨). 같은 파일을 다시 올리면 409, 없는 선수는 404.
    // 끝으로 limit=3과 nextBefore 커서로 두 페이지를 받아 페이지네이션을 확인한다.
    @Test
    fun `upload cycling files, reject running and corrupt ones, refuse duplicates`() {
        val id = createAthlete()
        val cycling = listOf(
            "Edge810-Vector-2013-08-16-15-35-10", "garmin-edge-500-activity", "garmin-edge-820-bike",
            "2019-02-17-062644-ELEMNT-297E-195-0", "sample-activity-indoor-trainer",
        )
        for (name in cycling) {
            val r = upload(id, name)
            assertEquals(201, r.status, "$name: ${r.body}")
            assertEquals("cycling", r.body!!.path("sport").asString())
        }
        val run = upload(id, "garmin-fenix-5-run")
        assertEquals(422, run.status)
        assertEquals("unsupported_sport", run.body!!.path("error").asString())
        for (name in listOf("activity-unexpected-eof", "activity-settings-corruptheader")) {
            val r = upload(id, name)
            assertEquals(400, r.status, name)
            assertEquals("invalid_fit", r.body!!.path("error").asString())
        }
        // Coros 파일은 저장되거나 잘못된 FIT으로 거절되거나 둘 중 하나여야 하고, 500은 안 된다.
        val coros = upload(id, "coros-pace-2-cycling-misaligned-fields")
        assertTrue(coros.status == 201 || coros.status == 400, "coros: ${coros.status}")
        val dup = upload(id, "garmin-edge-820-bike")
        assertEquals(409, dup.status)
        assertEquals(404, upload("00000000-0000-0000-0000-000000000000", "garmin-edge-820-bike").status)

        val list = get("/api/athletes/$id/activities?limit=3")
        assertEquals(200, list.status)
        assertEquals(3, list.body!!.path("items").size())
        val next = list.body!!.path("nextBefore").asString()
        val page2 = get("/api/athletes/$id/activities?limit=3&before=$next")
        assertTrue(page2.body!!.path("items").size() >= 2)
    }

    // 완료 기준 3: Edge810 라이딩을 올린 응답의 NP·TSS·20분 MMP가 디코더 + metrics 함수로 따로 계산한 값과
    // 같은지(NP·MMP 1e-3, TSS 1e-2). 기기가 세션에 적은 NP·TSS·IF와의 차이는 판정하지 않고 출력만 한다.
    // 이어서 samples의 every 처리와 활동 파워 커브를 확인한다.
    @Test
    fun `metrics of an uploaded ride equal the pure computation and are reported against the device`() {
        val id = createAthlete(ftp = 315) // Edge810 파일의 세션 메시지에 threshold_power 315가 적혀 있다
        val name = "Edge810-Vector-2013-08-16-15-35-10"
        val r = upload(id, name)
        assertEquals(201, r.status, r.body.toString())
        val metrics = r.body!!.path("metrics")
        assertTrue(metrics.path("normalizedPower").isNumber)

        // 서버를 거치지 않고 디코더와 지표 함수만으로 하는 독립 계산: 디코더 결과로 경과 시간·파워 배열을
        // 만들고 metrics 함수에 FTP 315를 넣는다. 서버의 저장·재조회 경로가 값을 바꾸지 않는지 보는 것이다.
        val fit = FitActivity.decode(File(fitDir, "$name.fit").readBytes())
        val t0 = fit.records.first().timestampUnix
        val secs = DoubleArray(fit.records.size) { (fit.records[it].timestampUnix - t0).toDouble() }
        val power = DoubleArray(fit.records.size) { (fit.records[it].power ?: 0).toDouble() }
        val samples = RideSamples(secs, power, fit.records.count { it.power == null })
        val recInt = RecordingInterval.estimate(secs)!!
        val np = PowerMetrics.normalizedPower(power, recInt)!!
        val tss = PowerMetrics.trainingStressScore(np, samples.size, recInt, 315.0)!!
        assertTrue(abs(np - metrics.path("normalizedPower").asDouble()) < 1e-3)
        assertTrue(abs(tss - metrics.path("tss").asDouble()) < 1e-2)
        assertEquals(315, metrics.path("ftpUsed").asInt())
        val mmp20 = MeanMaximalPower.peakPower(samples, recInt, 1200.0)!!
        assertTrue(abs(mmp20 - metrics.path("mmp").path("20m_critical_power").asDouble()) < 1e-3)

        // 기기가 보고한 값(session 메시지)은 출력만 하고 판정하지 않는다(api.md 완료 기준 3).
        val session = fit.sessions.first()
        println(
            "DEVICE_COMPARISON $name: NP ours=${"%.1f".format(np)} device=${session.normalizedPower} | " +
                "TSS ours=${"%.1f".format(tss)} device=${session.trainingStressScore} | IF ours=${"%.3f".format(np / 315)} device=${session.intensityFactor}",
        )

        // samples 엔드포인트의 간격(every)과 크기 처리. every=10이면 10초 구간마다 한 점이라 이 파일에서는
        // ceil(record 수 / 10)개를 기대한다(1초 간격 기록 기준). every=0은 400.
        val s = get("/api/activities/${r.body!!.path("id").asString()}/samples?every=10")
        assertEquals(200, s.status)
        assertEquals(10, s.body!!.path("every").asInt())
        assertEquals((fit.records.size + 9) / 10, s.body!!.path("secs").size())
        assertEquals(400, get("/api/activities/${r.body!!.path("id").asString()}/samples?every=0").status)

        // 활동 파워 커브: 로그 간격 점 수가 50~400 사이이고, 함께 들어 있는 표준 구간 중 20분 값이 위에서
        // 계산한 20분 MMP와 같은지.
        val curve = get("/api/activities/${r.body!!.path("id").asString()}/power-curve")
        assertEquals(200, curve.status)
        assertTrue(curve.body!!.path("points").size() in 50..400)
        assertTrue(abs(curve.body!!.path("standard").path("20m_critical_power").asDouble() - mmp20) < 1e-3)
    }

    // samples 점 수 상한(docs/api.md: 응답이 20,000점을 넘지 않도록 N을 자동으로 키운다). 1초 기록 20,001개(경과
    // 시간 0~20,000초)는 구간 번호가 0~20,000이라, 구간 폭을 길이 / 20,000으로 올림해 정하던 처음 계산으로는
    // every=1이 그대로 남아 20,001점이 나갔다. 상한을 넘지 않는지와, 상한 안에 드는 20,000개짜리 라이딩은
    // every=1 그대로인지 함께 본다.
    @Test
    fun `samples never exceed 20,000 points, even for a ride of exactly 20,000 seconds`() {
        val id = createAthlete()
        val long = uploadBytes(id, "long.fit", FitWriter.ride(20_001, startUnix = 1_600_200_000L).build())
        assertEquals(201, long.status, long.body.toString())
        val s = get("/api/activities/${long.body!!.path("id").asString()}/samples?every=1")
        assertEquals(200, s.status)
        assertTrue(s.body!!.path("secs").size() <= ActivityController.MAX_POINTS, "points: ${s.body!!.path("secs").size()}")
        assertEquals(2, s.body!!.path("every").asInt())

        val fits = uploadBytes(id, "fits.fit", FitWriter.ride(20_000, startUnix = 1_600_300_000L).build())
        assertEquals(201, fits.status, fits.body.toString())
        val f = get("/api/activities/${fits.body!!.path("id").asString()}/samples?every=1")
        assertEquals(1, f.body!!.path("every").asInt())
        assertEquals(20_000, f.body!!.path("secs").size())
    }

    // FTP 변경 재계산과 훈련 부하: FTP 300으로 만든 선수에 파워가 있는 ELEMNT 라이딩을 올리고 2000-01-01부터
    // FTP 250을 등록하면, 활동 1개가 다시 계산되고 TSS가 (300/250)²배가 돼야 한다(TSS ∝ 1/FTP²). 이어서 그날
    // 2026-10-15: 날짜 입력(기준일·조회 범위·FTP 적용일·목록 커서)이 1900~2100년 밖이면 400 bad_request. 그 전에는
    // FTP 적용일 +300000-01-01이 재계산 쿼리의 timestamptz 범위를 넘어 PostgreSQL 오류로 500이었고(IllegalArgument
    // Exception → 400 매핑을 정리하며 입력 검사를 다시 보다가 찾음), 1500년·9999년 같은 날짜는 그대로 받아들였다.
    // weeks 범위 밖은 전처럼 400(이제 BadRequestException).
    @Test
    fun `dates outside 1900-2100 are a 400, not a 500`() {
        val id = createAthlete()
        val results = linkedMapOf<String, Int>()
        results["training-load 1500"] = get("/api/athletes/$id/training-load?from=1500-01-01&to=1500-01-02").status
        results["ftp-estimate weeks=0"] = get("/api/athletes/$id/ftp-estimate?weeks=0").status
        results["ftp-estimate asOf 2101"] = get("/api/athletes/$id/ftp-estimate?asOf=2101-01-01").status
        results["activities before 1800"] = get("/api/athletes/$id/activities?before=1800-01-01T00:00:00Z").status
        results["threshold 9999"] = put("/api/athletes/$id/threshold", """{"effectiveFrom":"9999-01-01","ftpWatts":250}""").status
        results["threshold +300000"] = put("/api/athletes/$id/threshold", """{"effectiveFrom":"+300000-01-01","ftpWatts":250}""").status
        println("DATE_RANGE_RESULTS $results")
        for ((k, v) in results) assertEquals(400, v, k)
    }

    // 훈련 부하의 TSS가 활동 TSS와 같은지, from > to가 400인지, 52주 기간 커브가 10구간 이상인지, 삭제 뒤
    // 조회·재삭제가 404인지 본다.
    @Test
    fun `threshold change recomputes TSS and training load follows daily TSS`() {
        val id = createAthlete(ftp = 300)
        // 처음 이 테스트는 파워미터가 없는 Edge 500 파일을 써서 재계산이 0건이었다(AI의 테스트 가정 오류).
        // fitparse 기대값으로 파워 유무를 확인하고, 샘플 13,471개 중 13,346개에 파워가 있는 ELEMNT 파일로
        // 바꿨다(docs/ai-verification.md 2단계).
        val r = upload(id, "2019-02-17-062644-ELEMNT-297E-195-0")
        assertEquals(201, r.status, r.body.toString())
        val activityId = r.body!!.path("id").asString()
        val tssBefore = r.body!!.path("metrics").path("tss").asDouble()
        val started = r.body!!.path("startedAt").asString().substring(0, 10)

        val set = put("/api/athletes/$id/threshold", """{"effectiveFrom":"2000-01-01","ftpWatts":250}""")
        assertEquals(200, set.status, set.body.toString())
        assertEquals(1, set.body!!.path("recomputedActivities").asInt())
        val after = get("/api/activities/$activityId").body!!.path("metrics")
        assertEquals(250, after.path("ftpUsed").asInt())
        // TSS는 1 / FTP^2에 비례해 바뀐다.
        assertTrue(abs(after.path("tss").asDouble() - tssBefore * (300.0 / 250.0) * (300.0 / 250.0)) < 0.05)

        val load = get("/api/athletes/$id/training-load?from=$started&to=$started")
        assertEquals(200, load.status, load.body.toString())
        val day = load.body!!.path("days").get(0)
        assertEquals(started, day.path("date").asString())
        assertTrue(abs(day.path("tss").asDouble() - after.path("tss").asDouble()) < 1e-3)
        assertTrue(day.path("ctl").asDouble() > 0)
        assertEquals(400, get("/api/athletes/$id/training-load?from=2024-01-02&to=2024-01-01").status)

        val curve = get("/api/athletes/$id/power-curve?weeks=52&asOf=$started")
        assertEquals(200, curve.status)
        assertTrue(curve.body!!.path("points").size() >= 10)

        assertEquals(204, delete("/api/activities/$activityId").status)
        assertEquals(404, get("/api/activities/$activityId").status)
        assertEquals(404, delete("/api/activities/$activityId").status)
    }

    // 2026-10-24: FTP·시간대 변경 뒤의 재계산은 저장된 NP·샘플 수·기록 간격으로 IF·TSS만 다시 계산하고 적용 FTP가 바뀐 행만
    // 쓴다(그 전에는 활동마다 샘플 전체를 다시 읽어 NP·MMP·곡선까지 모두 다시 계산해 덮어썼다). 결과 값은 같아야 하므로 이
    // 테스트는 고치기 전 코드에서도 통과하는 동치 검사다: 변경 전후의 목록에서 NP·MMP는 그대로이고, ftpUsed는 활동의 현지
    // 날짜에 유효한 FTP, IF·TSS는 이전 값에 FTP 비율을 곱한 기대값(IF ∝ 1/FTP, TSS ∝ 1/FTP²)과 상대 1e-6 안이어야 한다.
    // IF·TSS·NP는 real(float4) 열이라 절대 오차가 아니라 상대 오차로 비교한다(float4 유효 숫자 약 7자리). recomputedActivities는
    // '다시 계산한 활동 수'라 적용 FTP가 그대로인 활동도 센다.
    @Test
    fun `threshold and time zone changes recompute only IF and TSS and leave NP and MMP untouched`() {
        val id = createAthlete(ftp = 300)
        // 2026-02-10~13 매일 01:00Z(서울 10:00, 선수 기본 시간대 Asia/Seoul)에 시작하는 30분 라이딩 4개, 파워는 서로 다르게.
        val first = Instant.parse("2026-02-10T01:00:00Z").epochSecond
        val ids = listOf(200, 250, 300, 220).mapIndexed { day, power ->
            val r = uploadBytes(id, "ride-$day.fit", FitWriter.ride(1_800, power = power, startUnix = first + day * 86_400L).build())
            assertEquals(201, r.status, r.body.toString())
            r.body!!.path("id").asString()
        }
        fun metricsById(): Map<String, JsonNode> =
            get("/api/athletes/$id/activities?limit=200").also { assertEquals(200, it.status) }.body!!.path("items")
                .associate { it.path("id").asString() to it.path("metrics") }
        fun assertClose(expected: Double, actual: Double, what: String) =
            assertTrue(abs(actual - expected) <= 1e-6 * abs(expected), "$what: expected $expected, got $actual")
        // 적용 FTP가 oldFtp → newFtp로 바뀐 활동: NP·MMP 그대로, IF·TSS는 비율대로.
        fun assertRescaled(before: JsonNode, after: JsonNode, oldFtp: Int, newFtp: Int, what: String) {
            assertEquals(oldFtp, before.path("ftpUsed").asInt(), what)
            assertEquals(newFtp, after.path("ftpUsed").asInt(), what)
            assertEquals(before.path("normalizedPower"), after.path("normalizedPower"), "$what normalizedPower")
            assertEquals(before.path("mmp"), after.path("mmp"), "$what mmp")
            val ratio = oldFtp.toDouble() / newFtp
            assertClose(before.path("intensityFactor").asDouble() * ratio, after.path("intensityFactor").asDouble(), "$what intensityFactor")
            assertClose(before.path("tss").asDouble() * ratio * ratio, after.path("tss").asDouble(), "$what tss")
        }

        val before = metricsById()
        assertEquals(4, before.size)
        // 02-12부터 FTP 240: 서울 날짜 02-12·02-13의 라이딩 두 개만 다시 계산되고 바뀐다. 02-10·02-11은 그대로다.
        val set = put("/api/athletes/$id/threshold", """{"effectiveFrom":"2026-02-12","ftpWatts":240}""")
        assertEquals(200, set.status, set.body.toString())
        assertEquals(2, set.body!!.path("recomputedActivities").asInt())
        val afterThreshold = metricsById()
        for (i in 0..1) assertEquals(before.getValue(ids[i]), afterThreshold.getValue(ids[i]), "ride $i must be untouched")
        for (i in 2..3) assertRescaled(before.getValue(ids[i]), afterThreshold.getValue(ids[i]), 300, 240, "ride $i after threshold")

        // 시간대를 UTC−12(Etc/GMT+12, tz 데이터베이스의 Etc 이름은 부호가 반대)로 바꾸면 01:00Z는 전날 13:00이다: 02-12 01:00Z
        // 라이딩은 현지 02-11이 되어 FTP 300으로 돌아가고, 02-13 01:00Z 라이딩은 현지 02-12라 240 그대로다. 모든 파워 활동이
        // 다시 계산되므로 recomputedActivities는 4다.
        val zone = put("/api/athletes/$id/time-zone", """{"timeZone":"Etc/GMT+12"}""")
        assertEquals(200, zone.status, zone.body.toString())
        assertEquals("Etc/GMT+12", zone.body!!.path("timeZone").asString())
        assertEquals(4, zone.body!!.path("recomputedActivities").asInt())
        val afterZone = metricsById()
        for (i in 0..1) assertEquals(before.getValue(ids[i]), afterZone.getValue(ids[i]), "ride $i must be untouched")
        assertRescaled(afterThreshold.getValue(ids[2]), afterZone.getValue(ids[2]), 240, 300, "ride 2 after zone change")
        assertRescaled(before.getValue(ids[2]), afterZone.getValue(ids[2]), 300, 300, "ride 2 back to its original FTP")
        assertEquals(afterThreshold.getValue(ids[3]), afterZone.getValue(ids[3]), "ride 3 keeps FTP 240")
    }

    // 측정 조건 안에서만 오차 안내(2026-10-24, docs/experiments.md 규칙 변경 14): 3단계 오차(dev 31 W·봉인 29 W·편향 −17 W)는
    // 6주 창에 파워 라이딩이 8개 이상인 예측 단위로만 잰 값이다. 라이딩 7개까지는 6주 창이어도 조건 밖(withinMeasuredRange
    // false, 오차 필드 세 개 없음, note는 재지 않았다고 적음), 8개가 되면 조건 안(세 숫자와 측정 조건), 같은 라이딩으로 12주
    // 창을 보면 다시 조건 밖이어야 한다. 고치기 전에는 weeks·라이딩 수와 관계없이 세 숫자를 항상 돌려줬다.
    @Test
    fun `ftp estimate reports the measured error only inside the measured condition`() {
        val id = createAthlete(ftp = 300)
        // 2026-02-10부터 하루 하나씩 30분 250 W 라이딩(01:00Z = 서울 10:00, 선수 기본 시간대 Asia/Seoul). asOf 2026-03-15의
        // 6주 창은 서울 0시 기준 [02-02, 03-16)이라 모두 창 안이고, 12주 창도 같은 라이딩만 담는다.
        val first = Instant.parse("2026-02-10T01:00:00Z").epochSecond
        fun uploadRide(day: Int) {
            val r = uploadBytes(id, "ride-$day.fit", FitWriter.ride(1_800, power = 250, startUnix = first + day * 86_400L).build())
            assertEquals(201, r.status, r.body.toString())
        }
        fun estimate(weeks: Int) = get("/api/athletes/$id/ftp-estimate?weeks=$weeks&asOf=2026-03-15")
            .also { assertEquals(200, it.status, it.body.toString()) }.body!!
        fun assertOutside(body: tools.jackson.databind.JsonNode) {
            val within = body.path("withinMeasuredRange")
            assertTrue(within.isBoolean && !within.asBoolean(), "withinMeasuredRange should be false: $body")
            for (f in listOf("devMaeWatts", "testMaeWatts", "testBiasWatts")) assertTrue(body.path(f).isMissingNode, "$f should be absent: $body")
            assertTrue(body.path("note").asString().contains("this window was not measured"), body.path("note").asString())
        }

        for (day in 0 until 7) uploadRide(day)
        val seven = estimate(6)
        assertEquals(7, seven.path("ridesInWindow").asInt())
        assertEquals(237.5, seven.path("ftpWatts").asDouble(), 1e-9) // R1 = 0.95 × 250
        assertOutside(seven)

        uploadRide(7)
        val eight = estimate(6)
        assertEquals(8, eight.path("ridesInWindow").asInt())
        assertTrue(eight.path("withinMeasuredRange").isBoolean && eight.path("withinMeasuredRange").asBoolean(), eight.toString())
        assertEquals(6, eight.path("measuredCondition").path("windowWeeks").asInt())
        assertEquals(8, eight.path("measuredCondition").path("minRidesInWindow").asInt())
        assertEquals(31, eight.path("devMaeWatts").asInt())
        assertEquals(29, eight.path("testMaeWatts").asInt())
        assertEquals(-17, eight.path("testBiasWatts").asInt())
        assertTrue(eight.path("note").asString().contains("(dev set)"), eight.path("note").asString())

        val twelve = estimate(12)
        assertEquals(8, twelve.path("ridesInWindow").asInt())
        assertOutside(twelve)
    }

    // ZAP 1차 스캔이 찾은 버그(없는 경로·메서드에 500)의 회귀 테스트: /, /api, /api/nope는 404, DELETE
    // /api/athletes는 405, 업로드 엔드포인트에 JSON은 415, limit=abc는 400. 응답에 nosniff·no-store 보안
    // 헤더가 붙는지도 본다.
    @Test
    fun `unknown routes, wrong methods and bad content types are 4xx with security headers`() {
        // ZAP 1차 스캔에서는 모르는 경로마다 500이 나왔다.
        assertEquals(404, get("/").status)
        assertEquals(404, get("/api").status)
        assertEquals(404, get("/api/nope").status)
        assertEquals(405, delete("/api/athletes").status)
        val id = createAthlete()
        assertEquals(415, call(org.springframework.http.HttpMethod.POST, "/api/athletes/$id/activities", json = "{}").status)
        assertEquals(400, get("/api/athletes/$id/activities?limit=abc").status)
        val headers = client.get().uri("/api/athletes/$id").exchange({ _, res -> res.headers }, false)
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"))
        assertEquals("no-store", headers.getFirst("Cache-Control"))
    }

    // 미디어 타입 오류 두 가지를 구분한다: 보낸 본문 형식을 받지 않으면 415(Unsupported Media Type), 응답을
    // 클라이언트가 받겠다는 형식(Accept)으로 만들 수 없으면 406(Not Acceptable). 이 API는 JSON만 돌려주므로
    // Accept: text/plain 요청은 406이어야 한다. 예외 처리기가 406을 415로 묶어 두었을 때도 응답은 Spring 기본
    // 처리로 406이었으므로, 이 테스트는 처리기를 정리한 뒤에도 같은 응답이 유지되는지 지킨다.
    @Test
    fun `an Accept header the API cannot satisfy is 406, an unsupported body type is 415`() {
        val id = createAthlete()
        assertEquals(415, call(org.springframework.http.HttpMethod.POST, "/api/athletes/$id/activities", json = "{}").status)
        val status = client.get().uri("/api/athletes/$id").accept(org.springframework.http.MediaType.TEXT_PLAIN)
            .exchange({ _, res -> res.statusCode.value() }, false)
        assertEquals(406, status)
    }

    // actuator health가 UP인지(완료 기준 5에서 쓰는 엔드포인트).
    @Test
    fun `health endpoint is up`() {
        val r = get("/actuator/health")
        assertEquals(200, r.status)
        assertEquals("UP", r.body!!.path("status").asString())
    }

    // 파워가 없는 FIT은 저장하되 지표는 비운다(api.md). 먼저 디코더로 이 파일에 정말 파워가 없는지 확인해
    // 테스트의 전제를 고정한다(전제가 깨지면 이 테스트는 파워 없는 경로를 검사하지 못하므로 실패시킨다). 처음
    // 버전은 파워가 있는 경우로도 갈라져, 그 가지의 assertNotNull(JsonNode.path는 null을 돌려주지 않는다)과 끝의
    // assertNull(null)이 항상 통과해 아무것도 검사하지 않았다.
    @Test
    fun `file without power is stored without metrics`() {
        // Edge 820 출퇴근 라이딩에는 파워미터가 없다: 저장은 되고 지표는 null이어야 한다.
        val fit = FitActivity.decode(File(fitDir, "garmin-edge-820-bike.fit").readBytes())
        assertTrue(fit.records.none { it.power != null }, "precondition: the Edge 820 sample has no power")
        val id = createAthlete()
        val r = upload(id, "garmin-edge-820-bike")
        assertEquals(201, r.status, r.body.toString())
        assertFalse(r.body!!.path("hasPower").asBoolean())
        assertTrue(r.body!!.path("metrics").isMissingNode, "metrics must be absent: ${r.body!!.path("metrics")}")
        // 다시 조회해도 지표가 없다(저장 단계에서 지표 행을 만들지 않았다).
        val stored = get("/api/activities/${r.body!!.path("id").asString()}")
        assertEquals(200, stored.status)
        assertTrue(stored.body!!.path("metrics").isMissingNode)
    }
}
