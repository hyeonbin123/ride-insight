package com.rideinsight.api

// [파일 역할] 모든 컨트롤러에서 올라온 예외를 HTTP 상태 코드와 `{ "error": 코드, "message": 설명 }` JSON으로
// 바꾸는 전역 예외 처리기다(docs/api.md의 오류 응답 형식).
// [왜 필요한가] FIT 디코더·서비스·저장소는 HTTP를 모르고 도메인 예외(FitDecodeException,
// UnsupportedSportException, DuplicateActivityException 등)만 던진다. 상태 코드 결정을 이 파일 하나에 모아
// 두면 api.md 완료 기준(400·404·409·413·422)과 5단계 변조 FIT 기대 코드(H8)를 한곳에서 맞출 수 있다.
// [주의] Spring은 선언 순서가 아니라 예외 타입이 가장 가깝게 맞는 처리기를 고른다. 맨 아래
// catch-all(Exception)은 다른 처리기가 맞지 않을 때만 쓰이는데, 2026-10-10 OWASP ZAP 1차 스캔에서 Spring의
// 404·405 예외에 맞는 처리기가 없어 catch-all이 이를 받아 존재하지 않는 경로에 500을 돌려준 버그가 잡혔다.
// 그래서 라우팅·프로토콜 예외를 따로 4xx로 매핑한다(docs/experiments.md 5단계, docs/ai-verification.md).
// [연결] ApiIntegrationTest와 TamperedFitTest가 여기서 정한 상태 코드와 error 값을 확인한다.

import com.rideinsight.fit.FitDecodeException
import com.rideinsight.fit.FitLimitException
import com.rideinsight.service.InvalidActivityException
import com.rideinsight.service.NotFoundException
import com.rideinsight.service.UnsupportedSportException
import com.rideinsight.storage.DuplicateActivityException
// 아래는 Spring MVC·멀티파트가 요청을 해석하는 단계에서 던지는 예외들이다: 본문 파싱 실패, 메서드·미디어 타입
// 불일치, 요청 파라미터·파트 누락, 경로 변수 타입 불일치, 경로 없음, 업로드 크기 초과. 각각 아래 처리기에서
// 4xx로 바꾼다.
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException

// 모든 @RestController에 적용되는 예외 처리 모음. 처리기의 반환값은 JSON 응답 본문이 된다.
@RestControllerAdvice
class ApiExceptionHandler {
    // 예상하지 못한 예외(500)만 스택과 함께 서버 로그에 남긴다. 응답 본문에는 스택을 넣지 않는다.
    private val log = LoggerFactory.getLogger(javaClass)

    // 공통 응답 생성 도우미: 상태 코드 + ErrorResponse(error, message).
    private fun respond(status: HttpStatus, error: String, message: String) =
        ResponseEntity.status(status).body(ErrorResponse(error, message))

    // 없는 선수·활동 → 404 not_found. 서비스 계층이 던진다.
    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = respond(HttpStatus.NOT_FOUND, "not_found", e.message ?: "not found")

    // FIT 구조 오류(헤더, 선언한 크기, 정의 없는 데이터 메시지, 중간에 끊긴 파일 등) → 400 invalid_fit.
    // 디코더 메시지를 붙여 어디가 깨졌는지 알려 준다(api.md: 디코딩 실패 400 + FitDecodeException 메시지).
    // 5단계 변조 파일 (1)(2)(4)(5)가 여기로 온다.
    @ExceptionHandler(FitDecodeException::class)
    fun fit(e: FitDecodeException) = respond(HttpStatus.BAD_REQUEST, "invalid_fit", "cannot decode FIT file: ${e.message}")

    // 처리 상한을 넘는 FIT(record 500,000개 또는 메시지 2,000,000개 초과) → 413 fit_limit_exceeded. 파일이 깨진
    // 것이 아니라 처리하기로 한 크기를 넘은 것이라 400과 구분한다(docs/experiments.md 5단계 '업로드 자원 증폭',
    // 규칙 변경 11). FitLimitException은 FitDecodeException의 하위 클래스이고, Spring은 가장 가까운 예외 타입의
    // 처리기를 고르므로 위의 400 처리기가 아니라 이 처리기가 쓰인다.
    @ExceptionHandler(FitLimitException::class)
    fun fitLimit(e: FitLimitException) = respond(HttpStatus.PAYLOAD_TOO_LARGE, "fit_limit_exceeded", "the FIT file is too large to process: ${e.message}")

    // 활동으로 쓸 수 없는 입력 → 400 invalid_activity: 빈 업로드 파일(컨트롤러가 디코딩 전에 거절), 디코딩은
    // 됐지만 record 메시지가 없거나 타임스탬프가 거꾸로 가는 파일. 변조 파일 (6)이 여기로 온다.
    @ExceptionHandler(InvalidActivityException::class)
    fun invalidActivity(e: InvalidActivityException) = respond(HttpStatus.BAD_REQUEST, "invalid_activity", e.message ?: "invalid")

    // 러닝·수영·걷기·하이킹 파일 → 422 unsupported_sport(api.md). 422(Unprocessable Content)는 요청 형식은
    // 맞지만 내용을 처리할 수 없다는 뜻이라, 깨진 파일(400)과 구분된다.
    @ExceptionHandler(UnsupportedSportException::class)
    fun sport(e: UnsupportedSportException) = respond(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_sport", e.message ?: "")

    // 같은 선수·같은 시작 시각의 활동이 이미 있음 → 409 duplicate_activity. activity 테이블의
    // unique(athlete_id, started_at) 위반을 ActivityRepository가 이 예외로 바꿔 던진다.
    @ExceptionHandler(DuplicateActivityException::class)
    fun duplicate(e: DuplicateActivityException) = respond(HttpStatus.CONFLICT, "duplicate_activity", e.message ?: "")

    // 10 MB를 넘는 업로드 → 413 file_too_large. 한도는 application.properties의
    // spring.servlet.multipart.max-file-size이고, 클라이언트가 연결 끊김 대신 실제 413을 받도록
    // server.tomcat.max-swallow-size=16MB도 두었다(Codex 3차 검토, 변조 파일 (7)).
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(e: MaxUploadSizeExceededException) =
        respond(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "the upload exceeds the 10 MB limit")

    // @Valid 요청 본문 검사 실패(빈 이름, 50~1000 W 밖의 FTP, M/F가 아닌 성별 등) → 400 validation. 필드별
    // 오류를 '필드: 이유; ...'로 이어 붙여 돌려준다.
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return respond(HttpStatus.BAD_REQUEST, "validation", details.ifEmpty { "invalid request body" })
    }

    // 그 밖의 잘못된 요청 → 400 bad_request: 코드의 require() 실패(IllegalArgumentException), JSON 본문 파싱
    // 실패, 경로·쿼리 값의 타입 불일치(UUID가 아닌 id, limit=abc), multipart에 file 파트 없음. 메시지가 여러
    // 줄이면 첫 줄만 돌려준다.
    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestPartException::class,
    )
    fun badRequest(e: Exception) = respond(HttpStatus.BAD_REQUEST, "bad_request", e.message?.lineSequence()?.first() ?: "bad request")

    // 존재하지 않는 경로(/, /api, /robots.txt 등) → 404 not_found. 라우팅·프로토콜 오류는 '예상하지 못한
    // 오류'가 아니라 4xx다. 2026-10-10 OWASP ZAP 1차 스캔에서는 이 처리기가 없어, 맨 아래
    // catch-all(Exception) 처리기가 NoResourceFoundException까지 받아 모르는 경로마다 500을 돌려줬다(응답의
    // 12%가 5xx). 추가한 뒤 2차 스캔에서 5xx가 0%가 되었다. ApiIntegrationTest의 unknown routes 테스트가 회귀를
    // 막는다.
    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun noRoute(e: Exception) = respond(HttpStatus.NOT_FOUND, "not_found", "no such endpoint")

    // 경로는 있지만 지원하지 않는 HTTP 메서드(예: DELETE /api/athletes) → 405 method_not_allowed.
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) =
        respond(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "method ${e.method} is not supported here")

    // 지원하지 않는 Content-Type(예: 업로드 엔드포인트에 JSON을 보냄) → 415 unsupported_media_type.
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaType(e: HttpMediaTypeNotSupportedException) =
        respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "unsupported content type")

    // 클라이언트의 Accept로는 응답을 만들 수 없음(예: Accept: text/plain, 이 API는 JSON만 돌려준다) → 406 Not
    // Acceptable. 같은 이유로 JSON 오류 본문도 받아 줄 수 없으므로 본문 없이 상태만 돌려준다. 처음에는 415와 한
    // 처리기에 묶여 415 + JSON 본문을 쓰려 했는데, 본문을 쓰지 못해 'Failure in @ExceptionHandler' 경고와 스택을
    // 로그에 남긴 뒤 Spring 기본 처리기가 406을 돌려줬다. 응답 코드는 처음부터 406이었고, 처리기가 직접 406을
    // 돌려주게 정리했다(2026-10-13).
    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun notAcceptable(e: HttpMediaTypeNotAcceptableException): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build()

    // 필수 쿼리 파라미터 누락과 multipart 해석 오류 → 400 bad_request.
    @ExceptionHandler(MissingServletRequestParameterException::class, MultipartException::class)
    fun missing(e: Exception) = respond(HttpStatus.BAD_REQUEST, "bad_request", e.message?.lineSequence()?.first() ?: "bad request")

    // 위 처리기에 맞지 않은 모든 예외 → 500 internal. 응답에는 일반 문구만 넣고 상세는 로그로만 남겨
    // 스택·SQL이 응답에 드러나지 않는다(Codex 2차 검토가 정상으로 확인한 항목).
    @ExceptionHandler(Exception::class)
    fun other(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("unhandled error", e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "unexpected error")
    }
}
