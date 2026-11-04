package com.rideinsight.api

// [파일 역할] 같은 GET 요청을 여러 번 보내도 응답 본문이 매번 같은지를 실제 서버 + PostgreSQL 컨테이너로 확인한다
// (2026-11-05 추가). real(float4) 열 값의 JSON 자릿수가 같은 요청의 반복 횟수에 따라 바뀌던 문제의 회귀 테스트다.
// [원인] PgJDBC는 한 커넥션에서 같은 SQL을 prepareThreshold(기본 5)번 실행한 뒤부터 서버 쪽 prepared statement로 바꾸고,
// 그때부터 결과 열을 binary로 받는다. float4를 텍스트로 받으면 PostgreSQL이 보낸 짧은 표기(예: 300.58676)를 그대로 Double로
// 읽지만, binary로 받으면 4바이트 float를 double로 넓혀 300.5867614746094가 된다. 그래서 같은 활동 상세와 훈련 부하가 앞쪽
// 요청과 뒤쪽 요청에서 다른 JSON이었다(같은 요청 12번 중 6번째부터). 고친 곳은 application.properties의
// binaryTransferDisable 설정이다.
// [설정] 이 클래스만 커넥션 풀을 1개로 둔다(@TestPropertySource → 별도 Spring 컨텍스트와 새 PostgreSQL 컨테이너). 풀이 크면
// 요청마다 다른 커넥션을 쓸 수 있어, 한 커넥션의 실행 횟수가 임계값에 닿는 때가 정해지지 않는다. 별도 컨텍스트라 다른 테스트
// 클래스가 같은 SQL을 미리 여러 번 돌려 둔 커넥션을 물려받지도 않는다.

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["spring.datasource.hikari.maximum-pool-size=1"])
class RepeatedReadsTest : ApiTestBase() {

    // 같은 요청을 12번 보낸다(임계값 5를 넉넉히 넘는 횟수). 활동 상세(활동·지표 행의 real 열), 샘플(배열 열, SQL에서 텍스트로
    // 바꿔 읽음), 훈련 부하(지표 행의 tss를 읽어 Java에서 더함) 세 경로를 본다. 비교는 첫 응답 본문과 문자열로 하고, 한 경로가
    // 달라도 나머지 경로까지 모두 본 뒤 달라진 경로를 한꺼번에 알린다.
    @Test
    fun `repeating the same GET returns the same body every time`() {
        val id = createAthlete(ftp = 315)
        val up = upload(id, "Edge810-Vector-2013-08-16-15-35-10")
        assertEquals(201, up.status, up.body.toString())
        val ride = up.body!!
        val activityId = ride.path("id").asString()
        // 훈련 부하는 선수 시간대(기본 Asia/Seoul)의 날짜로 묶는다. 이 라이딩은 UTC로 2013-08-16 18:05에 시작해 선수 날짜로는
        // 08-17이므로, UTC 날짜 앞뒤 하루를 넣어 라이딩이 든 날이 범위에 들어가게 한다(2026-11-05 첫 판은 UTC 날짜 하루만 불러
        // TSS가 없는 날을 비교했다). 아래에서 TSS가 있는 날이 응답에 있는지부터 확인해, 이 경로가 저장된 TSS를 실제로 읽는지 본다.
        val utcDate = LocalDate.parse(ride.path("startedAt").asString().substring(0, 10))
        val load = "/api/athletes/$id/training-load?from=${utcDate.minusDays(1)}&to=${utcDate.plusDays(1)}"
        val detail = "/api/activities/$activityId"
        assertTrue(get(detail).body!!.path("metrics").path("normalizedPower").isNumber, "the detail has no stored NP to compare")
        assertTrue(get(load).body!!.path("days").any { it.path("tss").asDouble() > 0 }, "no day with TSS in $load")

        val differing = mutableListOf<String>()
        for (path in listOf(detail, "/api/activities/$activityId/samples", load)) {
            val bodies = (1..12).map { n ->
                val r = get(path)
                assertEquals(200, r.status, "$path request $n: ${r.body}")
                r.body.toString()
            }
            val first = bodies.indexOfFirst { it != bodies.first() }
            if (first >= 0) differing += "$path: request ${first + 1} differs from request 1\n  1: ${bodies.first()}\n  ${first + 1}: ${bodies[first]}"
        }
        assertTrue(differing.isEmpty(), differing.joinToString("\n"))
    }
}
