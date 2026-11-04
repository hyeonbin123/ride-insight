package com.rideinsight.api

// [파일 역할] 같은 GET 요청을 여러 번 보내도 응답 본문이 매번 같은지를 실제 서버 + PostgreSQL 컨테이너로 확인한다
// (2026-11-05 추가). real(float4) 열 값의 JSON 자릿수가 같은 요청의 반복 횟수에 따라 바뀌던 문제의 회귀 테스트다.
// [원인] PgJDBC는 한 커넥션에서 같은 SQL을 prepareThreshold(기본 5)번 실행한 뒤부터 서버 쪽 prepared statement로 바꾸고,
// 그때부터 결과 열을 binary로 받는다. float4를 텍스트로 받으면 PostgreSQL이 보낸 짧은 표기(예: 300.58676)를 그대로 Double로
// 읽지만, binary로 받으면 4바이트 float를 double로 넓혀 300.5867614746094가 된다. 그래서 같은 활동 상세와 훈련 부하가 앞쪽
// 요청과 뒤쪽 요청에서 다른 JSON이었다(같은 요청 12번 중 6번째부터). 고친 곳은 application.properties의
// binaryTransferDisable 설정이다.
// [실패 위치] (2026-11-05 덧붙임) 갈리는 때는 요청 수가 아니라 SQL마다의 실행 횟수로 정해진다(그 SQL의 6번째 실행부터
// binary). 이 테스트는 반복 전에 활동 상세와 훈련 부하를 한 번씩 미리 불러(아래 선확인) 지표 SQL과 훈련 부하 SQL의 첫 실행을
// 쓰므로, 설정이 빠지면 반복의 5번째 요청에서 갈린다(실패 메시지 'request 5 differs from request 1'). 활동 행 SQL은 업로드
// 응답도 한 번 읽어 반복의 4번째 요청이 6번째 실행이지만, 이 픽스처의 활동 행 real 값(recIntSecs 1.0·intervalShare 1.0·
// durationSecs 4700.0)은 두 전송 방식에서 같은 값이라 드러나지 않는다.
// [설정] 이 클래스만 커넥션 풀을 1개로 둔다(@TestPropertySource → 별도 Spring 컨텍스트와 새 PostgreSQL 컨테이너). 풀이 크면
// 요청마다 다른 커넥션을 쓸 수 있어, 한 커넥션의 실행 횟수가 임계값에 닿는 때가 정해지지 않는다. 별도 컨텍스트라 다른 테스트
// 클래스가 같은 SQL을 미리 여러 번 돌려 둔 커넥션을 물려받지도 않는다.

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode

@TestPropertySource(properties = ["spring.datasource.hikari.maximum-pool-size=1"])
class RepeatedReadsTest : ApiTestBase() {

    // 같은 요청을 12번 보낸다(임계값 5를 넉넉히 넘는 횟수). 활동 상세(활동·지표 행의 real 열), 샘플(배열 열, SQL에서 텍스트로
    // 바꿔 읽음), 훈련 부하(지표 행의 tss를 읽어 Java에서 더함) 세 경로를 본다. 비교는 첫 응답 본문과 문자열로 하고, 한 경로가
    // 달라도 나머지 경로까지 모두 본 뒤 달라진 경로를 한꺼번에 알린다.
    // 비교 기준의 전제(2026-11-05 추가): 이 비교는 기준(반복의 첫 응답)이 아직 텍스트 전송으로 읽은 것일 때만 뜻이 있다. 같은
    // SQL이 반복 전에 이미 임계값을 넘게 실행되게 바뀌면(지금은 업로드·선확인으로 상세의 활동 행 SQL이 두 번, 지표·훈련 부하
    // SQL이 한 번) 12번이 모두 binary라 서로 같아져, 설정이 빠져도 통과하는 빈 검사가 된다. 그래서 real 열을 그대로 읽는 두
    // 경로(상세, 훈련 부하)는 기준 본문의 값이 텍스트 전송의 꼴인지 먼저 확인한다(assertReadOverText). 샘플은 SQL에서 ::text로
    // 바꿔 읽어 전송 방식과 무관하므로 이 확인이 없다.
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
            val baseline = mapper.readTree(bodies.first())
            when (path) {
                detail -> assertReadOverText(
                    path,
                    listOf("recIntSecs", "intervalShare", "durationSecs").associateWith { baseline.path(it) } +
                        listOf("normalizedPower", "intensityFactor", "tss", "averagePower", "workKj")
                            .associate { "metrics.$it" to baseline.path("metrics").path(it) },
                )
                // 선수 날짜 하나에 라이딩이 하나뿐이라 그날의 tss는 저장된 값 그대로다(0.0 + tss). CTL·ATL·TSB는 Java에서
                // 계산한 double이라 전송 꼴을 따질 수 없어 넣지 않는다.
                load -> assertReadOverText(
                    path,
                    baseline.path("days").filter { it.path("tss").asDouble() > 0 }
                        .associate { "tss@${it.path("date").asString()}" to it.path("tss") },
                )
            }
            val first = bodies.indexOfFirst { it != bodies.first() }
            if (first >= 0) differing += "$path: request ${first + 1} differs from request 1\n  1: ${bodies.first()}\n  ${first + 1}: ${bodies[first]}"
        }
        assertTrue(differing.isEmpty(), differing.joinToString("\n"))
    }

    // 기준 본문의 real(float4) 열 값이 텍스트 전송으로 읽은 꼴인지 확인한다. 텍스트로 받으면 PostgreSQL이 보낸 float의 가장 짧은
    // 십진 표기를 Double로 읽으므로 값이 `Float.toString`을 다시 읽은 값과 같고, binary로 받으면 float를 그대로 넓힌 값
    // (`toDouble()`)이다. 250.0처럼 float가 짧은 십진수로 정확히 나타나는 값은 두 꼴이 같아 어느 쪽인지 알 수 없으므로 빼고,
    // 두 꼴을 가를 수 있는 값이 하나도 없으면(픽스처가 바뀐 경우) 그 사실로 실패한다. 응답에 없는 열(null)도 뺀다.
    private fun assertReadOverText(path: String, values: Map<String, JsonNode>) {
        val forms = values.filterValues { it.isNumber }.mapValues { (_, node) ->
            val v = node.asDouble()
            val f = v.toFloat()
            val text = f.toString().toDouble()
            val widened = f.toDouble()
            when {
                text == widened -> "same"
                v == text -> "text"
                v == widened -> "binary"
                else -> "neither ($v)"
            }
        }
        val telling = forms.filterValues { it != "same" }
        assertTrue(telling.isNotEmpty(), "$path: no real column value tells text from binary transfer: $values")
        assertTrue(
            telling.values.all { it == "text" },
            "$path: the baseline (request 1) was not read over text transfer, so the comparison would pass even " +
                "without the setting. The same SQL probably ran past PgJDBC's prepareThreshold before the loop: $forms",
        )
    }
}
