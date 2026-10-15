package com.rideinsight.service

// [파일 역할] 업로드 동시 처리 상한(UploadGate, 규칙 변경 12)의 단위 테스트. 상한만큼은 동시에 들어가고, 넘는
// 요청은 대기 상한이 지나면 TooManyUploadsException이며, 차례는 예외가 나도 돌려주고, 기다린 요청은 앞의 업로드가
// 끝나면 들어간다. 스레드와 래치로 시점을 정확히 맞춰 시간에 기대지 않는다(대기 상한 0초 또는 넉넉한 10초).

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UploadGateTest {
    @Test
    fun `a request over the limit is refused after the wait`() {
        val gate = UploadGate(maxConcurrent = 2, maxWaitSeconds = 0)
        val inside = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            // 두 업로드가 문 안에서 멈춰 있는 동안 세 번째는 바로(대기 0초) 거절돼야 한다.
            repeat(2) { pool.submit { gate.run { inside.countDown(); release.await() } } }
            check(inside.await(10, TimeUnit.SECONDS))
            val e = assertFailsWith<TooManyUploadsException> { gate.run { "never" } }
            assertEquals(UploadGate.RETRY_AFTER_SECONDS, e.retryAfterSeconds)
            release.countDown()
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
        // 둘이 끝나면 다시 들어갈 수 있다.
        assertEquals("ok", gate.run { "ok" })
    }

    @Test
    fun `the permit is returned when the upload fails`() {
        val gate = UploadGate(maxConcurrent = 1, maxWaitSeconds = 0)
        assertFailsWith<IllegalStateException> { gate.run { error("decode failed") } }
        assertEquals(1, gate.run { 1 })
    }

    @Test
    fun `a waiting request gets in when the running one finishes`() {
        val gate = UploadGate(maxConcurrent = 1, maxWaitSeconds = 10)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit { gate.run { inside.countDown(); release.await() } }
            check(inside.await(10, TimeUnit.SECONDS))
            val waiting = pool.submit<String> { gate.run { "second" } }
            release.countDown()
            assertEquals("second", waiting.get(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
