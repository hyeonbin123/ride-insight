package com.rideinsight.api

// [파일 역할] 현지 날짜 기준(2026-10-15, 선수 시간대 기본 Asia/Seoul)의 UTC 경계 테스트. 같은 라이딩을
// 2026-10-14T21:30:00Z(한국 시각 10-15 06:30)에 시작하게 만들어, 선수 시간대에 따라 (1) 적용되는 FTP, (2) 훈련
// 부하(PMC)에 쌓이는 날짜, (3) 기간 파워 커브의 기준일 창이 달라지는지 확인한다. 요청 단위 tz 파라미터와 선수 시간대
// 변경(재계산), 모르는 시간대(400)도 본다.
// [왜] 2026-10-15 전에는 모든 날짜를 UTC로 잘라, 한국에서 오전 9시 전에 시작한 라이딩이 PMC의 전날에 쌓이고 그날부터
// 바꾼 FTP 대신 전날 FTP로 TSS가 계산될 수 있었다.
// [2026-10-24 추가] 현지 날짜는 모두 서버(java.time)가 센다. 그 전에는 PMC의 날짜 묶음과 워밍업 시작일만 SQL의
// `at time zone`이 셌는데, PostgreSQL은 tz 데이터베이스에 없는 고정 오프셋 이름('+09:00', 'UTC+09:00', 'GMT+09:00')을
// POSIX 규칙(+가 그리니치 서쪽)으로 읽어 Java와 반대 부호로 셌다(오류 없이 하루 어긋남). 아래 오프셋·전체 시간대·
// 서머타임 테스트가 이를 막는다. 고치기 전 코드에서 전체 시간대 테스트는 615개 이름 중 107개가 어긋났다: 테스트 DB
// 이미지(postgres:16)가 모르는 옛 별칭 96개('US/Eastern'·'ROK' 등)는 500, 오프셋 표기 10개는 반대 부호, 'EET'는
// PostgreSQL이 서머타임 없는 고정 약어로 읽어 날짜가 하루 달랐다.

import com.rideinsight.fit.FitWriter
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeZoneTest : ApiTestBase() {
    // 2026-10-14T21:30:00Z = 2026-10-15 06:30 KST. 1초 간격 20분 라이딩(250 W)이라 20분 MMP와 TSS가 나온다.
    private val start = OffsetDateTime.parse("2026-10-14T21:30:00Z").toEpochSecond()
    private fun ride(startUnix: Long = start) = FitWriter.ride(1_200, power = 250, startUnix = startUnix).build()

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

    // 쿼리 파라미터를 붙인 GET. 값은 URI 변수로 넣어 RestClient가 엄격하게 인코딩하게 한다('+' → %2B, ':' → %3A).
    // ApiTestBase.get처럼 경로 문자열에 그대로 쓰면 '+09:00'의 '+'는 서버에서 공백으로 읽히고, 미리 '%2B'로 적어 두면
    // RestClient의 템플릿 인코딩이 '%'를 다시 '%25'로 바꿔 보내므로, 오프셋 시간대를 tz로 보내려면 이렇게 해야 한다.
    // 응답은 ApiTestBase.call과 같은 방식(상태 코드 + JSON 본문)으로 돌려준다.
    private fun getWithQuery(path: String, query: Map<String, String>): ApiTestBase.Response =
        client.get().uri { b ->
            b.path(path)
            for (k in query.keys) b.queryParam(k, "{$k}")
            b.build(query)
        }.exchange({ _, res ->
            val text = res.body.readAllBytes().toString(Charsets.UTF_8)
            ApiTestBase.Response(res.statusCode.value(), if (text.isBlank()) null else mapper.readTree(text))
        }, false)

    // 훈련 부하 응답(from~to, 양 끝 포함). tz를 주면 요청 단위 시간대로 본다.
    private fun trainingLoad(id: String, from: String, to: String, tz: String? = null): ApiTestBase.Response {
        val query = linkedMapOf("from" to from, "to" to to)
        if (tz != null) query["tz"] = tz
        return getWithQuery("/api/athletes/$id/training-load", query)
    }

    // 날짜별 TSS(0보다 큰 날만). 기본 조회 범위는 라이딩 앞뒤 2026-10-13 ~ 10-16이다.
    private fun tssDays(id: String, tz: String? = null, from: String = "2026-10-13", to: String = "2026-10-16"): Map<String, Double> {
        val r = trainingLoad(id, from, to, tz)
        assertEquals(200, r.status, "tz=$tz: ${r.body}")
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
        assertEquals(setOf("2026-10-14"), tssDays(id, tz = "UTC").keys)
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

    // 2026-10-24: 고정 오프셋을 받은 선수. Java(ISO-8601)는 '+09:00'·'UTC+9'·'GMT+9'를 모두 UTC+9(그리니치 동쪽)로 본다.
    // 고치기 전에는 FTP 선택(Java)으로는 라이딩이 10-15인데 PMC(SQL)에는 10-14로 쌓였고, 다음 주 첫날 CTL도 하루를 더
    // 감쇠했다. 'Etc/GMT-9'(tz 데이터베이스의 Etc 이름은 부호가 반대라 UTC+9)와 'Asia/Tokyo'는 두 쪽이 원래 같게 세던 비교
    // 대상이다. 오류 없이 저장·응답되는 어긋남이라 날짜와 CTL 값을 직접 비교한다. Java가 정규화해 저장한 이름(응답의
    // timeZone)도 함께 확인한다.
    @Test
    fun `fixed offsets count east of Greenwich in every date the server computes`() {
        val zones = listOf("+09:00", "UTC+9", "GMT+9", "Etc/GMT-9", "Asia/Tokyo")
        val names = linkedMapOf<String, String>()
        val ftpUsed = linkedMapOf<String, Int>()
        val days = linkedMapOf<String, Set<String>>()
        val nextWeekCtl = linkedMapOf<String, Double>()
        var lastId = ""
        for (zone in zones) {
            val (id, ftp) = athleteWithRide(zone)
            lastId = id
            names[zone] = get("/api/athletes/$id").body!!.path("timeZone").asString()
            ftpUsed[zone] = ftp
            days[zone] = tssDays(id).keys
            // 워밍업: 조회 시작일이 라이딩 다음 주여도 첫날 CTL에 그 라이딩이 남아 있다. 같은 순간을 같은 현지 날짜로 세는
            // 선수끼리는 값이 같아야 한다.
            val later = trainingLoad(id, "2026-10-20", "2026-10-21")
            assertEquals(200, later.status, later.body.toString())
            nextWeekCtl[zone] = later.body!!.path("days").get(0).path("ctl").asDouble()
        }
        assertEquals(
            mapOf("+09:00" to "+09:00", "UTC+9" to "UTC+09:00", "GMT+9" to "GMT+09:00", "Etc/GMT-9" to "Etc/GMT-9", "Asia/Tokyo" to "Asia/Tokyo"),
            names,
        )
        assertEquals(zones.associateWith { 300 }, ftpUsed, "the FTP effective from the local date 2026-10-15 applies")
        assertEquals(zones.associateWith { setOf("2026-10-15") }, days, "PMC date of the ride")
        val tokyo = nextWeekCtl.getValue("Asia/Tokyo")
        assertTrue(tokyo > 0, "the warm-up keeps the ride in the CTL")
        assertEquals(zones.associateWith { tokyo }, nextWeekCtl, "first-day CTL after the warm-up")
        // 쿼리 파라미터의 '+'는 URL 규칙상 공백이라 '%2B'로 보내야 한다. 그대로 쓰면 ' 09:00'이 되어 400이다(docs/api.md).
        assertEquals(400, get("/api/athletes/$lastId/training-load?tz=+09:00").status)
        assertEquals(200, trainingLoad(lastId, "2026-10-13", "2026-10-16", "+09:00").status)
    }

    // 2026-10-24: 시간대를 고정 오프셋으로 바꾸면 TSS 재계산(Java)과 PMC가 같은 날짜를 쓴다. 서쪽 오프셋도 같은 의미다:
    // '-05:00'·'GMT-5'(UTC−5)이면 라이딩은 10-14 16:30이라 이전 FTP(200)와 10-14다. 고치기 전 PostgreSQL은 '-05:00'을
    // UTC+5로 읽어 PMC만 10-15로 셌다.
    @Test
    fun `changing the zone to a fixed offset recomputes and moves the PMC date the same way`() {
        val (id, ftpUsed) = athleteWithRide("UTC")
        assertEquals(200, ftpUsed)
        val results = linkedMapOf<String, Pair<Int, Set<String>>>()
        for (zone in listOf("+09:00", "-05:00", "GMT-5", "UTC+9")) {
            val changed = put("/api/athletes/$id/time-zone", """{"timeZone":"$zone"}""")
            assertEquals(200, changed.status, changed.body.toString())
            assertEquals(1, changed.body!!.path("recomputedActivities").asInt())
            val ftp = get("/api/athletes/$id/activities").body!!.path("items").get(0).path("metrics").path("ftpUsed").asInt()
            results[zone] = ftp to tssDays(id).keys
        }
        assertEquals(
            mapOf(
                "+09:00" to (300 to setOf("2026-10-15")),
                "-05:00" to (200 to setOf("2026-10-14")),
                "GMT-5" to (200 to setOf("2026-10-14")),
                "UTC+9" to (300 to setOf("2026-10-15")),
            ),
            results,
        )
    }

    // 2026-10-24: Java가 받는 모든 시간대 이름(ZoneId.getAvailableZoneIds()의 지역 이름 전부 + 고정 오프셋 표기 몇 가지)에서
    // 라이딩의 PMC 날짜가 Java의 현지 날짜(Instant.atZone(zone).toLocalDate())와 같은지 본다. 그 날짜 하루만 조회하면 TSS가
    // 있고, 다음 날 하루만 조회하면 TSS는 없지만 워밍업으로 CTL이 남아야 한다. 요청 단위 tz로 보내 선수 하나로 확인한다(요청
    // tz와 선수 시간대는 같은 계산 경로를 쓴다). 고치기 전에는 오프셋 표기가 PostgreSQL에서 반대 부호가 되었다.
    @Test
    fun `every zone Java accepts puts the ride on its Java local date`() {
        val (id, _) = athleteWithRide("Asia/Seoul")
        val offsets = listOf("Z", "+09:00", "-05:00", "+05:30", "+14:00", "-12:00", "UTC+9", "UTC-5", "GMT+9", "GMT-5", "UT+9")
        val zones = ZoneId.getAvailableZoneIds().sorted() + offsets
        val ride = Instant.ofEpochSecond(start)
        val wrong = linkedMapOf<String, String>()
        for (zone in zones) {
            val day = ride.atZone(ZoneId.of(zone)).toLocalDate()
            val on = trainingLoad(id, day.toString(), day.toString(), zone)
            val next = trainingLoad(id, day.plusDays(1).toString(), day.plusDays(1).toString(), zone)
            val onDay = on.body?.path("days")?.get(0)
            val nextDay = next.body?.path("days")?.get(0)
            val ok = on.status == 200 && next.status == 200 && onDay != null && nextDay != null &&
                onDay.path("tss").asDouble() > 0 && nextDay.path("tss").asDouble() == 0.0 && nextDay.path("ctl").asDouble() > 0
            if (!ok) wrong[zone] = "day=$day on=${on.status}/${onDay?.path("tss")} next=${next.status}/${nextDay?.path("tss")}"
        }
        println("ZONE_SWEEP zones=${zones.size} wrong=${wrong.size} ${wrong.entries.take(15)}")
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${zones.size} zones: ${wrong.entries.take(15)}")
    }

    // 2026-10-24: 서머타임 지역(Europe/Berlin)에서 하루의 경계가 현지 0시를 따르는지. 2026-03-29는 02:00 CET → 03:00 CEST로
    // 건너뛰어 23시간이고, 2026-10-25는 03:00 CEST → 02:00 CET로 되돌려 25시간이다(02:00~03:00이 두 번). 경계 바로 앞뒤(현지
    // 23:59와 0:00)와 두 번 오는 02:30에 라이딩을 두고, PMC의 날짜별 TSS가 Java의 현지 날짜별 라이딩 수와 맞는지, 23·25시간인
    // 날 하루만 조회해도 그날 라이딩만 담기는지 본다. 날짜 경계를 SQL에서 Java(반열린 구간 [현지 0시, 다음 날 현지 0시))로
    // 옮기며 하루가 어긋날 위험을 막는 테스트다(PostgreSQL도 Europe/Berlin을 알아 고치기 전에도 통과했다).
    @Test
    fun `PMC days follow local midnight across daylight saving changes`() {
        val zone = ZoneId.of("Europe/Berlin")
        val created = post("/api/athletes", """{"name":"dst","ftp":200,"timeZone":"Europe/Berlin"}""")
        assertEquals(201, created.status, created.body.toString())
        val id = created.body!!.path("id").asString()
        // 활동이 아직 없으면 첫 활동 시각이 없어(null) 워밍업 없이 모든 날이 0이다.
        val empty = trainingLoad(id, "2026-10-24", "2026-10-26")
        assertEquals(200, empty.status, empty.body.toString())
        assertEquals(3, empty.body!!.path("days").size())
        assertTrue(empty.body!!.path("days").all { it.path("tss").asDouble() == 0.0 && it.path("ctl").asDouble() == 0.0 })
        val starts = listOf(
            "2026-03-28T22:59:00Z", // 03-28 23:59 CET
            "2026-03-28T23:00:00Z", // 03-29 00:00 CET (23시간인 날의 시작)
            "2026-03-29T21:59:00Z", // 03-29 23:59 CEST
            "2026-03-29T22:00:00Z", // 03-30 00:00 CEST
            "2026-10-24T21:59:00Z", // 10-24 23:59 CEST
            "2026-10-24T22:00:00Z", // 10-25 00:00 CEST (25시간인 날의 시작)
            "2026-10-25T00:30:00Z", // 10-25 02:30 CEST (첫 번째 02:30)
            "2026-10-25T01:30:00Z", // 10-25 02:30 CET (두 번째 02:30)
            "2026-10-25T22:59:00Z", // 10-25 23:59 CET
            "2026-10-25T23:00:00Z", // 10-26 00:00 CET
        ).map(Instant::parse)
        var tss = 0.0
        for (t in starts) {
            val up = uploadBytes(id, "dst.fit", ride(t.epochSecond))
            assertEquals(201, up.status, up.body.toString())
            tss = up.body!!.path("metrics").path("tss").asDouble() // 같은 라이딩이라 모두 같은 값
        }
        // Java가 센 현지 날짜별 라이딩 수. 테스트의 전제(라이딩이 경계 앞뒤의 날짜에 놓였는지)도 함께 확인한다.
        val counts = starts.groupingBy { it.atZone(zone).toLocalDate().toString() }.eachCount()
        assertEquals(
            mapOf("2026-03-28" to 1, "2026-03-29" to 2, "2026-03-30" to 1, "2026-10-24" to 1, "2026-10-25" to 4, "2026-10-26" to 1),
            counts,
        )
        for ((from, to) in listOf("2026-03-28" to "2026-03-30", "2026-10-24" to "2026-10-26")) {
            val got = tssDays(id, from = from, to = to)
            val want = counts.filterKeys { it in from..to }
            assertEquals(want.keys, got.keys, "$from..$to")
            for ((d, n) in want) assertTrue(abs(got.getValue(d) - n * tss) < 1e-3, "$d: ${got[d]} vs ${n * tss}")
        }
        // 23·25시간인 날 하루만 조회: 앞뒤 날의 라이딩(현지 23:59·0:00)은 그날 TSS에 들어오지 않는다.
        for (d in listOf("2026-03-29", "2026-10-25")) {
            val days = trainingLoad(id, d, d).body!!.path("days")
            assertEquals(1, days.size(), d)
            assertTrue(abs(days.get(0).path("tss").asDouble() - counts.getValue(d) * tss) < 1e-3, "$d: ${days.get(0)}")
        }
    }
}
