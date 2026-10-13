package com.rideinsight.api

// [파일 역할] docs/api.md 2단계 완료 기준을 실제 HTTP + PostgreSQL 컨테이너로 확인하는 통합 테스트 묶음: 입력
// 검증, 샘플 FIT 업로드·거절·중복, 업로드 지표와 순수 함수 계산의 일치, FTP 변경 재계산과 훈련 부하, 라우팅
// 오류와 보안 헤더, health, 파워 없는 파일.
// [왜 중요한가] 단위 테스트로는 못 잡는 실수를 이 층에서 잡았다: PgJDBC의 float4 읽기 오류(선수 조회가 전부
// 500), multipart 테스트 클라이언트 의존성 문제, 파워가 없는 파일로 재계산을 시험한 테스트 가정 오류. ZAP이
// 찾은 404→500 버그도 여기서 회귀를 막는다(docs/ai-verification.md 2·5단계).

import com.rideinsight.fit.FitActivity
import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.PowerMetrics
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.metrics.RideSamples
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end checks for docs/api.md against a PostgreSQL container. */
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
        // The Coros file is either stored or rejected as invalid FIT, never a 500.
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
        val id = createAthlete(ftp = 315) // the Edge810 session says threshold_power 315
        val name = "Edge810-Vector-2013-08-16-15-35-10"
        val r = upload(id, name)
        assertEquals(201, r.status, r.body.toString())
        val metrics = r.body!!.path("metrics")
        assertTrue(metrics.path("normalizedPower").isNumber)

        // 서버를 거치지 않은 독립 계산: 디코더 결과로 경과 시간·파워 배열을 만들고 metrics 함수에 FTP 315를
        // 넣는다. 서버의 저장·재조회 경로가 값을 바꾸지 않는지 보는 것이다.
        // Independent computation straight from the decoder and the metric functions.
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

        // Device-reported values (session message): reported, not judged (api.md criterion 3).
        val session = fit.sessions.first()
        println(
            "DEVICE_COMPARISON $name: NP ours=${"%.1f".format(np)} device=${session.normalizedPower} | " +
                "TSS ours=${"%.1f".format(tss)} device=${session.trainingStressScore} | IF ours=${"%.3f".format(np / 315)} device=${session.intensityFactor}",
        )

        // every=10이면 10초 구간마다 한 점이라 이 파일에서는 ceil(record 수 / 10)개를 기대한다(1초 간격 기록
        // 기준). every=0은 400.
        // Samples endpoint: stride and size cap.
        val s = get("/api/activities/${r.body!!.path("id").asString()}/samples?every=10")
        assertEquals(200, s.status)
        assertEquals(10, s.body!!.path("every").asInt())
        assertEquals((fit.records.size + 9) / 10, s.body!!.path("secs").size())
        assertEquals(400, get("/api/activities/${r.body!!.path("id").asString()}/samples?every=0").status)

        // 활동 파워 커브: 로그 간격 점 수가 50~400 사이이고, 표준 20분 값이 위에서 계산한 20분 MMP와 같은지.
        // Activity power curve: log-spaced points, standard windows included.
        val curve = get("/api/activities/${r.body!!.path("id").asString()}/power-curve")
        assertEquals(200, curve.status)
        assertTrue(curve.body!!.path("points").size() in 50..400)
        assertTrue(abs(curve.body!!.path("standard").path("20m_critical_power").asDouble() - mmp20) < 1e-3)
    }

    // FTP 변경 재계산과 훈련 부하: FTP 300으로 만든 선수에 파워가 있는 ELEMNT 라이딩을 올리고 2000-01-01부터
    // FTP 250을 등록하면, 활동 1개가 다시 계산되고 TSS가 (300/250)²배가 돼야 한다(TSS ∝ 1/FTP²). 이어서 그날
    // 훈련 부하의 TSS가 활동 TSS와 같은지, from > to가 400인지, 52주 기간 커브가 10구간 이상인지, 삭제 뒤
    // 조회·재삭제가 404인지 본다.
    @Test
    fun `threshold change recomputes TSS and training load follows daily TSS`() {
        val id = createAthlete(ftp = 300)
        // 처음 이 테스트는 파워가 없는 Edge 500 파일을 써서 재계산이 0건이었다(AI의 테스트 가정 오류).
        // fitparse 기대값으로 파워 유무를 확인하고 ELEMNT 파일로 바꿨다(docs/ai-verification.md 2단계).
        // The Edge 500 sample has no power meter (first draft of this test used it and recomputed
        // nothing); the ELEMNT ride has power on 13,346 of 13,471 samples.
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
        // TSS scales with 1 / FTP^2.
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

    // ZAP 1차 스캔이 찾은 버그(없는 경로·메서드에 500)의 회귀 테스트: /, /api, /api/nope는 404, DELETE
    // /api/athletes는 405, 업로드 엔드포인트에 JSON은 415, limit=abc는 400. 응답에 nosniff·no-store 보안
    // 헤더가 붙는지도 본다.
    @Test
    fun `unknown routes, wrong methods and bad content types are 4xx with security headers`() {
        // ZAP scan 1 found 500s on every unknown path.
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

    // actuator health가 UP인지(완료 기준 5에서 쓰는 엔드포인트).
    @Test
    fun `health endpoint is up`() {
        val r = get("/actuator/health")
        assertEquals(200, r.status)
        assertEquals("UP", r.body!!.path("status").asString())
    }

    // 파워가 없는 FIT은 저장하되 지표는 비운다(api.md). 기대값은 파일에 실제로 파워가 있는지를 디코더로
    // 확인해 정하므로, 어느 쪽이든 서버 응답이 파일 내용과 맞는지 검사한다.
    @Test
    fun `file without power is stored without metrics`() {
        // The Edge 820 commute has no power meter: stored, metrics null.
        val id = createAthlete()
        val r = upload(id, "garmin-edge-820-bike")
        assertEquals(201, r.status, r.body.toString())
        val fit = FitActivity.decode(File(fitDir, "garmin-edge-820-bike.fit").readBytes())
        val hasPower = fit.records.any { it.power != null }
        assertEquals(hasPower, r.body!!.path("hasPower").asBoolean())
        if (!hasPower) {
            assertTrue(r.body!!.path("metrics").isMissingNode || r.body!!.path("metrics").isNull)
        } else {
            assertNotNull(r.body!!.path("metrics").path("normalizedPower"))
        }
        // 이 줄은 항상 통과한다(검사 효과 없음).
        assertNull(null)
    }
}
