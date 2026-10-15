package com.rideinsight.api

// [파일 역할] 현지 날짜 기준(2026-10-15, 선수 시간대 기본 Asia/Seoul)의 UTC 경계 테스트. 같은 라이딩을
// 2026-10-14T21:30:00Z(한국 시각 10-15 06:30)에 시작하게 만들어, 선수 시간대에 따라 (1) 적용되는 FTP, (2) 훈련
// 부하(PMC)에 쌓이는 날짜, (3) 기간 파워 커브의 기준일 창이 달라지는지 확인한다. 요청 단위 tz 파라미터와 선수 시간대
// 변경(재계산), 모르는 시간대(400)도 본다.
// [왜] 2026-10-15 전에는 모든 날짜를 UTC로 잘라, 한국에서 오전 9시 전에 시작한 라이딩이 PMC의 전날에 쌓이고 그날부터
// 바꾼 FTP 대신 전날 FTP로 TSS가 계산될 수 있었다.

import com.rideinsight.fit.FitWriter
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeZoneTest : ApiTestBase() {
    // 2026-10-14T21:30:00Z = 2026-10-15 06:30 KST. 1초 간격 20분 라이딩(250 W)이라 20분 MMP와 TSS가 나온다.
    private val start = OffsetDateTime.parse("2026-10-14T21:30:00Z").toEpochSecond()
    private fun ride() = FitWriter.ride(1_200, power = 250, startUnix = start).build()

    // FTP 200으로 선수를 만들고 2026-10-15부터 FTP 300을 등록한 뒤 라이딩을 올린다. 업로드 응답의 ftpUsed를 돌려준다.
    private fun athleteWithRide(timeZone: String?): Pair<String, Int> {
        val tz = timeZone?.let { ""","timeZone":"$it"""" } ?: ""
        val created = post("/api/athletes", """{"name":"tz","ftp":200$tz}""")
        assertEquals(201, created.status, created.body.toString())
        val id = created.body!!.path("id").asString()
        assertEquals(200, put("/api/athletes/$id/threshold", """{"effectiveFrom":"2026-10-15","ftpWatts":300}""").status)
        val up = uploadBytes(id, "kst-morning.fit", ride())
        assertEquals(201, up.status, up.body.toString())
        return id to up.body!!.path("metrics").path("ftpUsed").asInt()
    }

    // 날짜별 TSS(0보다 큰 날만).
    private fun tssDays(id: String, query: String = ""): Map<String, Double> {
        val r = get("/api/athletes/$id/training-load?from=2026-10-13&to=2026-10-16$query")
        assertEquals(200, r.status, r.body.toString())
        return r.body!!.path("days").filter { it.path("tss").asDouble() > 0 }
            .associate { it.path("date").asString() to it.path("tss").asDouble() }
    }

    @Test
    fun `a Korean morning ride counts on the Korean date by default`() {
        val (id, ftpUsed) = athleteWithRide(null)
        assertEquals("Asia/Seoul", get("/api/athletes/$id").body!!.path("timeZone").asString())
        assertEquals(300, ftpUsed, "the FTP effective from 2026-10-15 (KST date of the ride) applies")
        assertEquals(setOf("2026-10-15"), tssDays(id).keys)
        // 요청 단위로 UTC로 보면 같은 라이딩이 10-14에 쌓인다(저장된 TSS 값은 그대로).
        assertEquals(setOf("2026-10-14"), tssDays(id, "&tz=UTC").keys)
        // 기간 커브: asOf=2026-10-14, 1주. 한국 날짜로는 라이딩이 10-15라 창 밖, UTC로는 창 안.
        assertEquals(0, get("/api/athletes/$id/power-curve?weeks=1&asOf=2026-10-14").body!!.path("points").size())
        assertTrue(get("/api/athletes/$id/power-curve?weeks=1&asOf=2026-10-14&tz=UTC").body!!.path("points").size() > 0)
    }

    @Test
    fun `an athlete in UTC keeps the previous behaviour, and changing the zone recomputes`() {
        val (id, ftpUsed) = athleteWithRide("UTC")
        assertEquals(200, ftpUsed, "on the UTC date (2026-10-14) the older FTP applies")
        assertEquals(setOf("2026-10-14"), tssDays(id).keys)
        val changed = put("/api/athletes/$id/time-zone", """{"timeZone":"Asia/Seoul"}""")
        assertEquals(200, changed.status, changed.body.toString())
        assertEquals(1, changed.body!!.path("recomputedActivities").asInt())
        val activity = get("/api/athletes/$id/activities").body!!.path("items").get(0)
        assertEquals(300, activity.path("metrics").path("ftpUsed").asInt())
        assertEquals(setOf("2026-10-15"), tssDays(id).keys)
    }

    @Test
    fun `unknown time zones are a 400`() {
        assertEquals(400, post("/api/athletes", """{"name":"x","timeZone":"Mars/Olympus"}""").status)
        val id = createAthlete()
        assertEquals(400, put("/api/athletes/$id/time-zone", """{"timeZone":"not a zone"}""").status)
        assertEquals(400, get("/api/athletes/$id/training-load?tz=Nowhere/Town").status)
    }
}
