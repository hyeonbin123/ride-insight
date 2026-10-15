package com.rideinsight.api

// [파일 역할] 예외 → HTTP 상태 매핑을 Spring이 실제로 쓰는 규칙(ExceptionHandlerMethodResolver: 가장 가까운 예외
// 타입의 처리기)으로 확인하는 단위 테스트. 2026-10-15에 'IllegalArgumentException 전체를 400으로' 하던 매핑을 걷어
// 냈다: 입력 검사는 BadRequestException으로만 400이 되고, 코드 안의 require() 실패(IllegalArgumentException)나
// IllegalStateException 같은 내부 오류는 맨 아래 처리기(500 + 로그)로 가야 한다. 그래야 '500 없음' 같은 주장이
// 숨은 버그를 400으로 덮지 않는다.

import com.rideinsight.fit.FitDecodeException
import com.rideinsight.fit.FitLimitException
import com.rideinsight.service.BadRequestException
import com.rideinsight.service.InvalidActivityException
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver

class ApiExceptionMappingTest {
    private val resolver = ExceptionHandlerMethodResolver(ApiExceptionHandler::class.java)

    // 예외 하나가 어느 처리 메서드로 가는지(메서드 이름).
    private fun handlerFor(e: Exception): String? = resolver.resolveMethodByThrowable(e)?.name

    @Test
    fun `input validation is 400, internal argument errors are not`() {
        assertEquals("badRequest", handlerFor(BadRequestException("weeks must be between 1 and 104")))
        assertEquals("other", handlerFor(IllegalArgumentException("secs must be non-decreasing at index 3")))
        assertEquals("other", handlerFor(IllegalStateException("unexpected")))
        assertEquals("other", handlerFor(java.time.DateTimeException("Invalid date")))
    }

    @Test
    fun `fit errors keep their codes`() {
        assertEquals("fit", handlerFor(FitDecodeException("truncated")))
        assertEquals("fitLimit", handlerFor(FitLimitException("more than 500000 record messages in file")))
        assertEquals("invalidActivity", handlerFor(InvalidActivityException("no records")))
    }
}
