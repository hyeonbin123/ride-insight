package com.rideinsight.fit

// [파일 역할] FIT 디코더의 처리 상한(docs/experiments.md 5단계 '업로드 자원 증폭', 규칙 변경 11)을 확인하는 단위
// 테스트. record 500,000개는 받고 500,001개는 FitLimitException, 전체 메시지 2,000,000개를 넘는 파일도
// FitLimitException, 서비스가 쓰지 않는 메시지·필드는 담지 않는지, 정상 샘플 FIT가 상한에서 충분히 먼지 본다.
// [왜 중요한가] 상한이 정상 파일을 거절하면 서비스가 쓸모없어지고, 상한이 빠지면 4 MB 조작 파일 하나가 힙 512 MB
// 서버를 OutOfMemoryError로 만든다(2026-10-15 적용 전 측정). 경계값을 정확히 고정해 둔다.

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FitLimitsTest {
    // 압축 타임스탬프 record로 record 수를 정확히 맞춘다. tinyRecords(n)은 머리 메시지 2개(file_id, session) +
    // record n − 2개를 만든다.
    private fun withRecords(records: Int) = FitStressFiles.tinyRecords(records + 2)

    @Test
    fun `a file at the record limit decodes`() {
        val act = FitActivity.decode(withRecords(FitDecoder.MAX_RECORD_MESSAGES))
        assertEquals(FitDecoder.MAX_RECORD_MESSAGES, act.records.size)
    }

    @Test
    fun `one record over the limit is rejected as a limit, not as a broken file`() {
        val e = assertFailsWith<FitLimitException> { FitActivity.decode(withRecords(FitDecoder.MAX_RECORD_MESSAGES + 1)) }
        assertTrue(e.message!!.contains("record messages"), e.message)
    }

    // 담지 않는 메시지(전역 번호 0xFF00, 필드 없음)를 1바이트씩 2,000,001개 넣은 파일. record 상한에는 걸리지
    // 않고 전체 메시지 상한에 걸린다.
    @Test
    fun `the total message limit still applies to messages that are not kept`() {
        val w = FitWriter.ride(10)
        w.define(3, 0xFF00, emptyList())
        w.raw(ByteArray(FitDecoder.MAX_MESSAGES + 1) { 3 })
        val e = assertFailsWith<FitLimitException> { FitDecoder.decode(w.build()) }
        assertTrue(e.message!!.contains("messages in file"), e.message)
    }

    // 필드 255개짜리 메시지는 구조만 확인하고 담지 않는다. 앞의 정상 라이딩 60초는 그대로 읽힌다.
    @Test
    fun `wide messages that the service does not read are not kept`() {
        val file = FitDecoder.decode(FitStressFiles.wideMessages(1_000_000))
        assertTrue(file.totalMessages > 3_000, "the wide messages were read: ${file.totalMessages}")
        assertTrue(file.messages.none { it.globalMessage == 0xFF00 }, "unknown messages must not be kept")
        assertEquals(60, file.records.size)
        assertEquals(60, FitActivity.from(file).records.size)
    }

    // 정상 샘플 FIT 9개(손상 파일은 디코딩 실패로 건너뜀)가 두 상한에서 충분히 먼지. 가장 긴 파일도 record
    // 13,471개, 메시지 24,050개라 상한의 3%와 2% 아래다.
    @Test
    fun `sample files are far below both limits`() {
        val dir = File("src/test/resources/fit")
        var maxRecords = 0
        var maxMessages = 0
        for (f in dir.listFiles { x -> x.name.endsWith(".fit") }!!) {
            val file = try {
                FitDecoder.decode(f.readBytes())
            } catch (e: FitLimitException) {
                throw AssertionError("${f.name} hit a limit: ${e.message}", e)
            } catch (_: FitDecodeException) {
                continue // 일부러 손상된 샘플(구조 오류)
            }
            maxRecords = maxOf(maxRecords, file.records.size)
            maxMessages = maxOf(maxMessages, file.totalMessages)
        }
        assertEquals(13_471, maxRecords)
        assertTrue(maxRecords * 10 < FitDecoder.MAX_RECORD_MESSAGES)
        assertTrue(maxMessages * 10 < FitDecoder.MAX_MESSAGES)
    }
}
