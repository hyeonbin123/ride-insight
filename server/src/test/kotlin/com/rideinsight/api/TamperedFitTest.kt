package com.rideinsight.api

// [파일 역할] 5단계 H8(변조 FIT 수용성) 판정 테스트. 테스트용 인코더 FitWriter로 정상 파일을 만든 뒤 한
// 가지씩 망가뜨려 업로드하고, 미리 적어 둔 상태 코드와 같은지, 500이 하나도 없는지
// 확인한다(docs/experiments.md 5단계). 결과: 7종 모두 기대 코드(H8 지지). 2026-10-15에 처리 상한을 넘는 조작 파일
// 3종((8)~(10), 5단계 '업로드 자원 증폭')을 더했다.
// [왜 중요한가] 업로드 파일은 신뢰할 수 없는 입력이다. 디코더가 처리하지 못한 예외가 500으로 새거나, 조작된
// 크기 필드로 버퍼 밖을 읽는 일이 없어야 한다.
// [한계] 규칙과 이 테스트가 같은 커밋(fba8f44)에 들어가, '규칙을 먼저 커밋하고 쟀다'는 것은 공개 이력만으로
// 입증되지 않는다(experiments.md에 적어 둔 한계).

import com.rideinsight.fit.FitActivity
import com.rideinsight.fit.FitDecoder
import com.rideinsight.fit.FitStressFiles
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

    // 2026-10-15 추가: 퍼징 입력(FitFuzz)을 실제 HTTP 업로드로 보내 DB 저장까지 지나게 한다. 바이트 변이 300건과
    // 구조를 아는 무작위 파일 150건(고정 seed). 응답은 정해 둔 코드(201 저장, 400 invalid_fit·invalid_activity,
    // 409 같은 시작 시각, 413 상한, 422 거절 종목)만 허용하고 500은 하나도 없어야 한다. DB 앞 단계의 수천 건 퍼징은
    // FitFuzzTest가 하고, 여기서는 DB 열 범위처럼 실제 저장에서만 드러나는 문제를 본다. 같은 선수에게 같은 시작
    // 시각이 쌓이면 409만 늘어나므로 25건마다 새 선수를 만든다.
    @Test
    fun `fuzzed uploads only get the documented status codes`() {
        val seeds = com.rideinsight.fit.FitFuzz.seeds()
        val rng = java.util.Random(20261017L)
        val allowed = setOf("201 null", "400 invalid_fit", "400 invalid_activity", "409 duplicate_activity", "413 fit_limit_exceeded", "422 unsupported_sport")
        val counts = sortedMapOf<String, Int>()
        val bad = ArrayList<String>()
        var athlete = createAthlete()
        for (i in 0 until 450) {
            if (i % 25 == 0) athlete = createAthlete()
            val (source, bytes) = if (i < 300) {
                val (name, seed) = seeds[rng.nextInt(seeds.size)]
                name to com.rideinsight.fit.FitFuzz.mutate(seed, rng)
            } else {
                "structured" to com.rideinsight.fit.FitFuzz.structured(rng)
            }
            val c = code(uploadBytes(athlete, "fuzz-$i.fit", bytes))
            counts.merge(c, 1, Int::plus)
            if (c !in allowed) bad += "#$i from $source: $c"
        }
        println("FIT_FUZZ_HTTP n=450 statuses=$counts")
        assertTrue(bad.isEmpty(), bad.take(10).joinToString("\n"))
    }

    // 2026-10-15 추가(docs/experiments.md 5단계 '업로드 자원 증폭', H11의 상태 코드 부분): 구조는 올바르지만 처리
    // 상한을 넘는 파일. (8) 2바이트 record 200만 개(4 MB)와 (9) record 500,001개는 413 fit_limit_exceeded,
    // (10) 서비스가 쓰지 않는 255필드 메시지로 채운 10 MB 가까운 파일은 담지 않고 201로 저장한다. 적용 전에는
    // (8)이 힙 512 MB 서버에서 OutOfMemoryError(500)였다.
    @Test
    fun `files over the processing limits get 413 and wide unknown messages are skipped`() {
        val id = createAthlete()
        val results = linkedMapOf(
            "8-two-million-tiny-records" to code(uploadBytes(id, "tiny.fit", FitStressFiles.tinyRecords(2_000_000))),
            "9-one-record-over-the-limit" to code(uploadBytes(id, "limit.fit", FitStressFiles.tinyRecords(FitDecoder.MAX_RECORD_MESSAGES + 3))),
            "10-wide-unknown-messages" to code(uploadBytes(id, "wide.fit", FitStressFiles.wideMessages(10_000_000))),
        )
        println("FIT_LIMIT_RESULTS " + results.entries.joinToString(" | ") { "${it.key}=${it.value}" })
        assertEquals("413 fit_limit_exceeded", results["8-two-million-tiny-records"])
        assertEquals("413 fit_limit_exceeded", results["9-one-record-over-the-limit"])
        assertEquals("201 null", results["10-wide-unknown-messages"])
    }
}
