package com.rideinsight.api

// [파일 역할] 쓰기 경로(업로드 저장·FTP 변경·시간대 변경·삭제)가 선수 행 잠금을 기다리는 시간에 상한이 있고, 한 선수의 잠금
// 대기가 다른 선수의 업로드를 막지 않는지를 실제 서버 + PostgreSQL 컨테이너로 확인한다(2026-10-24). 잠금은 다른 커넥션(= 다른
// 트랜잭션)에서 `select ... for update`로 직접 잡아 '같은 선수의 다른 요청이 오래 걸리는 상태'를 흉내 낸다.
// [왜] 그 전에는 업로드가 UploadGate 허가(동시 2개)를 쥔 채 트랜잭션 첫 문장으로 선수 행을 잠갔고(디코딩도 그 뒤), 잠금
// 대기에는 상한이 없었다. 같은 선수의 업로드 두 건이 잠금을 기다리면 허가 두 개가 모두 묶여 다른 모든 선수의 업로드가 30초 뒤
// 429가 됐다. 삭제는 선수 행을 잠그지 않아 재계산과 겹치면 FK 위반(500)이 될 수 있었다.
// [설정] 이 클래스만 잠금 대기 상한 2초, 업로드 대기 상한 5초로 돈다(@TestPropertySource → 별도 Spring 컨텍스트). 잠금 상한이
// 업로드 대기 상한보다 짧아야 잠금에 막힌 업로드가 허가를 돌려준 뒤 다른 선수의 업로드가 그 허가를 얻을 수 있다.

import com.rideinsight.fit.FitWriter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.util.LinkedMultiValueMap
import tools.jackson.databind.JsonNode

@TestPropertySource(properties = ["ride-insight.athlete-lock-timeout=2s", "ride-insight.upload.max-wait-seconds=5"])
class AthleteLockTimeoutTest : ApiTestBase() {
    // 선수 행을 직접 잠그는 커넥션은 서버와 같은 DataSource(테스트 컨테이너)에서 얻는다.
    @Autowired lateinit var dataSource: DataSource

    // 2026-03-01 01:00Z부터 하루 하나씩 10분 라이딩(1초 간격, 250 W). 같은 선수의 시작 시각이 겹치면 409 duplicate_activity가
    // 되므로 날짜(day)로 구분한다.
    private val start = Instant.parse("2026-03-01T01:00:00Z").epochSecond
    private fun ride(day: Int) = FitWriter.ride(600, power = 250, startUnix = start + day * 86_400L).build()

    // 상태 코드·본문에 Retry-After 헤더까지 담은 응답(ApiTestBase.call은 헤더를 돌려주지 않는다).
    class Reply(val status: Int, val body: JsonNode?, val retryAfter: String?) {
        val error: String? get() = body?.path("error")?.asString()
    }

    // ApiTestBase.call과 같은 요청 방식(JSON 본문 또는 file 파트 하나)으로 보내고 Retry-After 헤더도 돌려준다.
    private fun send(method: HttpMethod, path: String, json: String? = null, fileName: String? = null, bytes: ByteArray? = null): Reply {
        val spec = client.method(method).uri(path)
        if (json != null) spec.contentType(MediaType.APPLICATION_JSON).body(json)
        if (fileName != null) {
            val parts = LinkedMultiValueMap<String, Any>()
            val partHeaders = HttpHeaders()
            partHeaders.setContentDispositionFormData("file", fileName)
            partHeaders.contentType = MediaType.APPLICATION_OCTET_STREAM
            parts.add("file", HttpEntity(ByteArrayResource(bytes!!), partHeaders))
            spec.contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
        }
        return spec.exchange({ _, res ->
            val text = res.body.readAllBytes().toString(Charsets.UTF_8)
            Reply(res.statusCode.value(), if (text.isBlank()) null else mapper.readTree(text), res.headers.getFirst(HttpHeaders.RETRY_AFTER))
        }, false)
    }

    private fun sendUpload(athleteId: String, day: Int) =
        send(HttpMethod.POST, "/api/athletes/$athleteId/activities", fileName = "day-$day.fit", bytes = ride(day))

    private fun assertBusy(r: Reply, what: String) {
        assertEquals(409, r.status, "$what: ${r.body}")
        assertEquals("athlete_busy", r.error, what)
        assertTrue((r.retryAfter?.toIntOrNull() ?: 0) >= 1, "$what: Retry-After header '${r.retryAfter}'")
    }

    /**
     * 선수 행을 다른 커넥션의 트랜잭션에서 `for update`로 잡은 상태. 서버의 요청은 이 행을 잠글 때 여기서 풀어 줄 때까지
     * 기다린다(잠금 대기 상한까지). [close]가 롤백해 잠금을 푼다.
     */
    private inner class HeldAthleteRow(athleteId: String) : AutoCloseable {
        private val connection = dataSource.connection.apply { autoCommit = false }

        init {
            connection.prepareStatement("select id from athlete where id = ? for update").use { st ->
                st.setObject(1, UUID.fromString(athleteId))
                st.executeQuery().use { rs -> check(rs.next()) { "athlete $athleteId not found" } }
            }
        }

        override fun close() {
            connection.rollback()
            connection.close()
        }
    }

    // 선수 A의 행이 잠겨 있는 동안 A 업로드 2건과 B 업로드 1건을 보낸다. A 두 건은 잠금 상한(2초) 뒤 409 athlete_busy +
    // Retry-After로 끝나 허가를 돌려주고, 그 허가를 받은 B는 201이어야 한다(업로드 대기 상한 5초 안). 같은 잠금을 기다리는 FTP
    // 변경·시간대 변경·삭제도 409다. 잠금이 풀리면 같은 요청이 모두 정상 처리돼야 한다(허가 누수·커넥션에 남은 설정 없음).
    // 고치기 전 코드에서는 A 두 건이 허가를 쥔 채 상한 없이 기다려 B가 5초 뒤 429였고, 잠금이 풀린 뒤 A 두 건은 201, 삭제는
    // 잠금을 보지 않아 바로 204였다.
    @Test
    fun `a locked athlete gets 409 athlete_busy after the lock timeout and does not block other athletes' uploads`() {
        val a = createAthlete(name = "A")
        val b = createAthlete(name = "B")
        val existing = sendUpload(a, 0)
        assertEquals(201, existing.status, existing.body.toString())
        val existingId = existing.body!!.path("id").asString()

        val pool = Executors.newFixedThreadPool(3)
        try {
            HeldAthleteRow(a).use {
                val a1 = pool.submit(Callable { sendUpload(a, 1) })
                val a2 = pool.submit(Callable { sendUpload(a, 2) })
                // 두 업로드가 허가를 얻고 잠금을 기다리기 시작할 시간을 준다(디코딩은 수 ms). 일찍 보내도 B는 201이어야 하므로
                // 통과 방향으로는 시간에 기대지 않는다.
                Thread.sleep(500)
                val b1 = pool.submit(Callable { sendUpload(b, 1) })
                val rb = b1.get(20, TimeUnit.SECONDS)
                assertEquals(201, rb.status, "upload of another athlete: ${rb.body}")
                assertBusy(a1.get(20, TimeUnit.SECONDS), "first upload of the locked athlete")
                assertBusy(a2.get(20, TimeUnit.SECONDS), "second upload of the locked athlete")

                assertBusy(send(HttpMethod.PUT, "/api/athletes/$a/threshold", """{"effectiveFrom":"2026-01-01","ftpWatts":260}"""), "threshold change")
                assertBusy(send(HttpMethod.PUT, "/api/athletes/$a/time-zone", """{"timeZone":"UTC"}"""), "time zone change")
                assertBusy(send(HttpMethod.DELETE, "/api/activities/$existingId"), "deletion")
                assertEquals(200, get("/api/activities/$existingId").status, "the activity must still exist")
            }
        } finally {
            pool.shutdownNow()
        }

        // 잠금이 풀린 뒤: 같은 파일(앞의 시도는 롤백돼 중복이 아니다)이 201, FTP 변경 200, 삭제 204.
        val again = sendUpload(a, 1)
        assertEquals(201, again.status, again.body.toString())
        val set = send(HttpMethod.PUT, "/api/athletes/$a/threshold", """{"effectiveFrom":"2026-01-01","ftpWatts":260}""")
        assertEquals(200, set.status, set.body.toString())
        assertEquals(2, set.body!!.path("recomputedActivities").asInt())
        assertEquals(204, send(HttpMethod.DELETE, "/api/activities/$existingId").status)
        assertEquals(1, get("/api/athletes/$a/activities").body!!.path("items").size())
    }

    // 같은 선수에 업로드·삭제·FTP 변경·시간대 변경이 동시에 섞여도 500이 없어야 한다. 선수 행 잠금이 모두를 한 줄로 세우므로
    // 각 요청은 정상 코드이거나(잠금을 2초 넘게 기다린 드문 경우) 409 athlete_busy다. 끝난 뒤 FTP를 한 번 더 바꾸면 남은 모든
    // 활동이 그 FTP로 계산돼 있어야 한다(재계산이 삭제된 활동을 쓰려다 실패하거나 일부만 쓰고 끝나지 않았다는 뜻).
    @Test
    fun `uploads, deletions and threshold changes of one athlete interleave without a 500`() {
        val id = createAthlete(ftp = 250)
        val toDelete = (0 until 6).map { day ->
            val r = sendUpload(id, day)
            assertEquals(201, r.status, r.body.toString())
            r.body!!.path("id").asString()
        }
        val results = ConcurrentLinkedQueue<Pair<String, Reply>>()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val tasks = listOf(
                Callable { for (day in 10 until 16) results += "upload" to sendUpload(id, day) },
                Callable { for (activityId in toDelete) results += "delete" to send(HttpMethod.DELETE, "/api/activities/$activityId") },
                Callable {
                    for (i in 0 until 6) {
                        val ftp = if (i % 2 == 0) 300 else 250
                        results += "threshold" to send(HttpMethod.PUT, "/api/athletes/$id/threshold", """{"effectiveFrom":"2000-01-01","ftpWatts":$ftp}""")
                        val zone = if (i % 2 == 0) "UTC" else "Asia/Seoul"
                        results += "time-zone" to send(HttpMethod.PUT, "/api/athletes/$id/time-zone", """{"timeZone":"$zone"}""")
                    }
                },
            )
            for (f in pool.invokeAll(tasks)) f.get(120, TimeUnit.SECONDS) // 작업 스레드의 예외를 여기서 드러낸다
        } finally {
            pool.shutdownNow()
        }
        val expected = mapOf("upload" to 201, "delete" to 204, "threshold" to 200, "time-zone" to 200)
        for ((what, r) in results) {
            assertTrue(r.status < 500, "$what: ${r.status} ${r.body}")
            assertTrue(r.status == expected.getValue(what) || (r.status == 409 && r.error == "athlete_busy"), "$what: ${r.status} ${r.body}")
        }
        assertEquals(24, results.size)

        // 마지막으로 한 번 더 바꾼 FTP가 남은 모든 활동에 적용돼 있어야 한다(IF = NP / FTP).
        val final = send(HttpMethod.PUT, "/api/athletes/$id/threshold", """{"effectiveFrom":"2000-01-01","ftpWatts":275}""")
        assertEquals(200, final.status, final.body.toString())
        val items = get("/api/athletes/$id/activities?limit=200").body!!.path("items")
        assertEquals(items.size(), final.body!!.path("recomputedActivities").asInt())
        assertTrue(items.size() >= 1, "the new uploads must remain")
        for (item in items) {
            val m = item.path("metrics")
            assertEquals(275, m.path("ftpUsed").asInt(), item.toString())
            val expectedIf = m.path("normalizedPower").asDouble() / 275
            assertTrue(abs(m.path("intensityFactor").asDouble() - expectedIf) <= 1e-5 * expectedIf, item.toString())
        }
    }
}
