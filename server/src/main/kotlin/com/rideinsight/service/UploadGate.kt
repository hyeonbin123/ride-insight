package com.rideinsight.service

// [파일 역할] FIT 업로드의 디코딩·저장을 서버 전체에서 동시에 몇 개까지만 하도록 묶는 문(세마포어). 차례를 얻지
// 못한 업로드는 도착 순서대로 기다리고(공정한 세마포어), 정해 둔 시간 안에 차례가 오지 않으면
// TooManyUploadsException(API 429 + Retry-After)으로 돌려보낸다.
// [왜 필요한가] 디코더와 저장 경로를 가볍게 고친 뒤에도 상한 가까운 파일(record 50만 개)의 업로드 한 건은 90 MB
// 남짓의 힙을 쓰고, 동시에 처리하는 수에 비례해 늘어난다. 2026-10-15 측정에서 이런 파일 5개를 동시에 올리자 힙
// 512 MB 서버의 사용 힙이 511 MB까지 올랐다(docs/experiments.md 5단계 '업로드 동시 처리 상한', 규칙 변경 12).
// 동시 처리 수를 묶으면 최악의 힙 사용이 '동시 수 × 한 건'으로 정해진다.
// [설정] ride-insight.upload.max-concurrent(기본 2), ride-insight.upload.max-wait-seconds(기본 30). 컨트롤러는 이
// 문을 통과한 뒤에야 업로드 바이트를 메모리로 읽으므로, 기다리는 업로드는 multipart 임시 파일에만 있다.
// [주의] 기다리는 동안 요청 스레드(Tomcat)를 붙잡는다. 대기 상한(30초)이 그 시간을 묶는다.

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 동시에 처리하는 업로드 수의 상한. [run]은 차례를 얻은 뒤 block을 실행하고 끝나면(예외가 나도) 차례를 돌려준다.
 * [maxWaitSeconds] 안에 차례를 얻지 못하면 [TooManyUploadsException].
 */
@Component
class UploadGate(
    @Value("\${ride-insight.upload.max-concurrent:2}") maxConcurrent: Int,
    @Value("\${ride-insight.upload.max-wait-seconds:30}") private val maxWaitSeconds: Long,
) {
    init {
        require(maxConcurrent >= 1) { "ride-insight.upload.max-concurrent must be at least 1" }
    }

    // fair = true: 먼저 기다린 업로드가 먼저 차례를 얻는다(늦게 온 업로드가 앞지르지 않는다).
    private val permits = Semaphore(maxConcurrent, true)

    fun <T> run(block: () -> T): T {
        val acquired = try {
            permits.tryAcquire(maxWaitSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // 서버가 멈추는 중 등으로 대기가 끊기면 인터럽트 표시를 되살리고 '바쁨'으로 답한다.
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) throw TooManyUploadsException(RETRY_AFTER_SECONDS)
        try {
            return block()
        } finally {
            permits.release()
        }
    }

    companion object {
        /** 429 응답의 Retry-After(초). 상한 가까운 업로드 한 건이 끝나는 데 걸리는 시간 정도로 잡았다. */
        const val RETRY_AFTER_SECONDS = 5
    }
}

// 동시에 처리하는 업로드가 꽉 차서 대기 상한 안에 차례가 오지 않음 → 429 too_many_uploads + Retry-After.
class TooManyUploadsException(val retryAfterSeconds: Int) :
    RuntimeException("too many uploads are being processed; retry after $retryAfterSeconds seconds")
