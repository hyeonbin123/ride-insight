package com.rideinsight.api

// [파일 역할] 5단계 H8(변조 FIT 수용성) 판정 테스트. 테스트용 인코더 FitWriter로 정상 파일을 만든 뒤 한
// 가지씩 망가뜨려 업로드하고, 미리 적어 둔 상태 코드와 같은지, 500이 하나도 없는지
// 확인한다(docs/experiments.md 5단계). 결과: 7종 모두 기대 코드(H8 지지).
// [왜 중요한가] 업로드 파일은 신뢰할 수 없는 입력이다. 디코더가 처리하지 못한 예외가 500으로 새거나, 조작된
// 크기 필드로 버퍼 밖을 읽는 일이 없어야 한다.
// [한계] 규칙과 이 테스트가 같은 커밋(fba8f44)에 들어가, '규칙을 먼저 커밋하고 쟀다'는 것은 공개 이력만으로
// 입증되지 않는다(experiments.md에 적어 둔 한계).

import com.rideinsight.fit.FitActivity
import com.rideinsight.fit.FitWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/experiments.md 5단계 H8: tampered FIT files get the expected status, never 500. */
class TamperedFitTest : ApiTestBase() {
    // 응답을 '상태 코드 error값' 문자열로 요약해 기대값과 한 번에 비교한다(예: '400 invalid_fit', 성공 응답은
    // error가 없어 '201 null').
    private fun code(r: Response) = "${r.status} ${r.body?.path("error")?.asString(null)}"

    // 기준선: FitWriter가 만든 정상 라이딩(120초)이 디코더에서 record 120개·CRC 정상으로 읽히고 업로드도
    // 201인지. 이것이 통과해야 아래 결과를 '변조 때문'이라고 말할 수 있다.
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

    // 변조 7종: (1) 데이터 크기 과대, (2) 과소, (3) CRC 변조, (4) 정의 없는 데이터 메시지, (5) 필드 수 255인
    // 정의 뒤 파일 끝, (6) 타임스탬프 역행, (7) 10 MB + 1바이트. 기대: (3)만 201 + crcValid=false(거절이
    // 아니라 표시), (6)은 400 invalid_activity, (7)은 413 file_too_large, 나머지는 400 invalid_fit.
    @Test
    fun `tampered files never produce a 500`() {
        val id = createAthlete()
        val good = FitWriter.ride(60)
        val cases = linkedMapOf(
            "1-declared-size-too-large" to good.build(declaredDataSize = 100_000),
            "2-declared-size-too-small" to good.build(declaredDataSize = 10),
            "3-broken-crc" to good.build(breakCrc = true),
            // (4) 레코드 헤더 0x03 = 로컬 타입 3의 일반 데이터 메시지인데 등록된 정의가 없다. (5) 정의 헤더
            // 0x40, 전역 메시지 20(record), 필드 수 0xFF(255)인데 뒤에 2바이트뿐이라 정의를 끝까지 읽을 수
            // 없다.
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
            // (3)은 저장되되 crcValid가 false로 표시돼야 한다.
            if (name == "3-broken-crc" && r.status == 201) assertFalse(r.body!!.path("crcValid").asBoolean(), "crcValid must be false")
        }
        // (7) 10 MB multipart 업로드 한도를 1바이트 넘는 파일. 처음 테스트는 413 대신 연결 끊김도 거절로
        // 인정했는데, 그러면 413과 서버 오류(비정상 종료)를 구분할 수 없다는 Codex 3차 검토 지적으로
        // server.tomcat.max-swallow-size를 두고 실제 413만 인정하게 고쳤다. 이 설정이 있으면 Tomcat이 본문의
        // 나머지를 끝까지 읽어 주므로 클라이언트가 실제 413 응답을 받는다.
        val big = ByteArray(10 * 1024 * 1024 + 1)
        results["7-over-10mb"] = code(uploadBytes(id, "big.fit", big))
        // 결과 전체를 한 줄로 출력해 실행 기록에 남긴 뒤 하나씩 판정한다.
        println("TAMPERED_FIT_RESULTS " + results.entries.joinToString(" | ") { "${it.key}=${it.value}" })
        for ((name, exp) in expected) assertEquals(exp, results[name], name)
        assertEquals("413 file_too_large", results["7-over-10mb"])
        // 최종 조건: 어떤 변조도 500을 내지 않는다(H8).
        assertTrue(results.values.none { it.startsWith("500") }, "no 500s: $results")
    }
}
