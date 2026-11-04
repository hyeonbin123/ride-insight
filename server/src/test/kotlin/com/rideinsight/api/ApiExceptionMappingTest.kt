package com.rideinsight.api

// [파일 역할] 예외 → HTTP 상태 매핑을 Spring이 실제로 쓰는 규칙(ExceptionHandlerMethodResolver: 가장 가까운 예외
// 타입의 처리기)으로 확인하는 단위 테스트. 2026-10-15에 'IllegalArgumentException 전체를 400으로' 하던 매핑을 걷어
// 냈다: 입력 검사는 BadRequestException으로만 400이 되고, 코드 안의 require() 실패(IllegalArgumentException)나
// IllegalStateException 같은 내부 오류는 맨 아래 처리기(500 + 로그)로 가야 한다. 그래야 '500 없음' 같은 주장이
// 숨은 버그를 400으로 덮지 않는다.
// 2026-11-05 추가: 선수 잠금 대기(409 athlete_busy + Retry-After)와 요청 해석 단계 400의 예외 종류별 처리 메서드·고정
// 문구도 여기서 고정한다. 통합 테스트(ApiIntegrationTest, AthleteLockTimeoutTest)가 대부분을 실제 요청으로 확인하지만,
// 필수 쿼리 파라미터 누락(missingParameter)처럼 지금 API에서는 실제 요청으로 만들 수 없는 처리기는 이 단위 테스트만
// 지킨다. 이미 맞는 동작을 고정하는 회귀 테스트라 처리기 코드는 바꾸지 않았다.

import com.rideinsight.fit.FitDecodeException
import com.rideinsight.fit.FitLimitException
import com.rideinsight.service.BadRequestException
import com.rideinsight.service.InvalidActivityException
import com.rideinsight.service.TooManyUploadsException
import com.rideinsight.storage.AthleteBusyException
import com.rideinsight.storage.DuplicateActivityException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.springframework.core.MethodParameter
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.MissingServletRequestPartException

class ApiExceptionMappingTest {
    private val resolver = ExceptionHandlerMethodResolver(ApiExceptionHandler::class.java)

    // 예외 하나가 어느 처리 메서드로 가는지(메서드 이름).
    private fun handlerFor(e: Exception): String? = resolver.resolveMethodByThrowable(e)?.name

    // MethodArgumentTypeMismatchException은 실패한 컨트롤러 인자의 MethodParameter를 받는다. 실제 컨트롤러 메서드에 묶이지
    // 않도록 이 테스트 안의 빈 메서드(경로 변수 id: UUID 하나)로 만든다. 처리기는 e.name(파라미터 이름)만 쓴다.
    @Suppress("unused", "UNUSED_PARAMETER")
    fun sampleEndpoint(id: UUID) = Unit

    private fun uuidParameter() = MethodParameter(javaClass.getMethod("sampleEndpoint", UUID::class.java), 0)

    // 400 bad_request 응답 하나를 확인한다: 상태, error 값, 고정 문구. 그리고 예외 원문에만 있던 내부 이름(Java 타입·DTO
    // 클래스·라이브러리 메시지)이 응답 문구에 섞이지 않았는지 본다(2026-10-24에 고친 노출, ApiExceptionHandler 머리 주석).
    private fun assertFixedBadRequest(r: ResponseEntity<ErrorResponse>, message: String, vararg hidden: String) {
        assertEquals(400, r.statusCode.value())
        assertEquals("bad_request", r.body!!.error)
        assertEquals(message, r.body!!.message)
        for (h in hidden) assertFalse(r.body!!.message.contains(h), "response message leaks '$h'")
    }

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

    // 업로드 동시 처리 상한(규칙 변경 12): 429 too_many_uploads와 Retry-After 헤더.
    @Test
    fun `a full upload gate is a 429 with Retry-After`() {
        assertEquals("tooManyUploads", handlerFor(TooManyUploadsException(5)))
        val r = ApiExceptionHandler().tooManyUploads(TooManyUploadsException(5))
        assertEquals(429, r.statusCode.value())
        assertEquals("5", r.headers.getFirst("Retry-After"))
        assertEquals("too_many_uploads", r.body!!.error)
    }

    // 선수 잠금 대기 상한(2026-10-24, AthleteRepository.lock): 409 athlete_busy와 Retry-After 헤더(초). 같은 409인
    // duplicate_activity와는 처리 메서드와 error 값으로 갈린다. 화면은 error 값으로 안내 문장을 고른다(web/src/format.ts).
    @Test
    fun `a busy athlete is a 409 athlete_busy with Retry-After, apart from duplicate_activity`() {
        assertEquals("athleteBusy", handlerFor(AthleteBusyException(3)))
        assertEquals("duplicate", handlerFor(DuplicateActivityException("activity already exists")))
        val r = ApiExceptionHandler().athleteBusy(AthleteBusyException(3))
        assertEquals(409, r.statusCode.value())
        assertEquals("3", r.headers.getFirst("Retry-After"))
        assertEquals("athlete_busy", r.body!!.error)
    }

    // 요청 해석 단계(Spring·Jackson·multipart)의 400: 예외 종류마다 처리 메서드와 고정 문구(2026-10-24). 예외에는 실제로 그
    // 단계에서 나는 꼴의 원문(타입 이름·DTO 클래스 이름·라이브러리 메시지)을 넣고, 응답에는 그 원문이 나가지 않는지 본다.
    @Test
    fun `request parsing errors are 400 bad_request with fixed messages`() {
        val handler = ApiExceptionHandler()

        val unreadable = HttpMessageNotReadableException(
            "JSON parse error: Cannot construct instance of `com.rideinsight.api.ThresholdRequest`",
            MockHttpInputMessage(ByteArray(0)),
        )
        assertEquals("unreadableBody", handlerFor(unreadable))
        assertFixedBadRequest(handler.unreadableBody(unreadable), "malformed JSON body", "com.rideinsight", "JSON parse error")

        val mismatch = MethodArgumentTypeMismatchException(
            "abc", UUID::class.java, "id", uuidParameter(), IllegalArgumentException("Invalid UUID string: abc"),
        )
        assertEquals("typeMismatch", handlerFor(mismatch))
        assertFixedBadRequest(handler.typeMismatch(mismatch), "'id' has an invalid value", "java.util.UUID", "Invalid UUID")

        val part = MissingServletRequestPartException("file")
        assertEquals("missingPart", handlerFor(part))
        assertFixedBadRequest(handler.missingPart(part), "multipart part 'file' is required", "Required part")

        // 지금 API의 쿼리 파라미터는 모두 선택이거나 기본값이 있어 실제 요청으로는 나지 않는다(처리기 주석). 생기면 같은 기준이다.
        val param = MissingServletRequestParameterException("weeks", "int")
        assertEquals("missingParameter", handlerFor(param))
        assertFixedBadRequest(handler.missingParameter(param), "'weeks' is required", "int", "Required request parameter")

        val multipart = MultipartException("Failed to parse multipart servlet request")
        assertEquals("multipart", handlerFor(multipart))
        assertFixedBadRequest(handler.multipart(multipart), "malformed multipart request", "servlet")
    }

    // 업로드 크기 초과는 MultipartException의 하위 클래스지만 더 가까운 타입의 413 처리기로 가야 한다(400 malformed multipart가
    // 아님). 한도는 application.properties의 spring.servlet.multipart.max-file-size(10 MB).
    @Test
    fun `an oversized upload is 413 file_too_large, not the multipart 400`() {
        val e = MaxUploadSizeExceededException(10L * 1024 * 1024)
        assertEquals("tooLarge", handlerFor(e))
        val r = ApiExceptionHandler().tooLarge(e)
        assertEquals(413, r.statusCode.value())
        assertEquals("file_too_large", r.body!!.error)
    }
}
