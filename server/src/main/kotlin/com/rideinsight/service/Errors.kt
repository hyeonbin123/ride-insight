package com.rideinsight.service

// [파일 역할] 서비스 계층이 던지는 도메인 예외 네 가지. 서비스는 HTTP를 모르고 무슨 일이 일어났는지만 알리고,
// 상태 코드로 바꾸는 일은 api/ApiExceptionHandler가 맡는다. RuntimeException이라 @Transactional 메서드 안에서
// 던지면 트랜잭션이 롤백된다.

// 없는 선수·활동 → 404 not_found.
class NotFoundException(message: String) : RuntimeException(message)

// 사이클링이 아닌 거절 대상 종목(ActivityService.REJECTED_SPORTS: 러닝·수영·걷기·하이킹) → 422
// unsupported_sport.
class UnsupportedSportException(message: String) : RuntimeException(message)

// 활동으로 쓸 수 없는 입력 → 400 invalid_activity: 빈 업로드 파일(컨트롤러가 디코딩 전에 거절), 디코딩은
// 됐지만 record 메시지가 없거나 타임스탬프가 거꾸로 가는 파일.
class InvalidActivityException(message: String) : RuntimeException(message)

// 요청 값이 API가 받기로 한 범위·형식을 벗어남 → 400 bad_request. 컨트롤러·서비스가 입력을 검사해 '의도해서'
// 던지는 예외다. 2026-10-15 전에는 이 자리에 require()(IllegalArgumentException)를 쓰고 예외 처리기가
// IllegalArgumentException 전체를 400으로 바꿔, 지표 코드 같은 내부의 require 실패(버그)도 400으로 가려졌다. 이제
// 입력 검사는 이 예외로만 400이 되고, 그 밖의 IllegalArgumentException은 500으로 드러난다.
class BadRequestException(message: String) : RuntimeException(message)

/**
 * 날짜 입력(기준일·조회 범위·FTP 적용일·목록 커서)이 받을 수 있는 범위(1900-01-01 ~ 2100-12-31)인지 확인한다.
 * 벗어나면 [BadRequestException]. 이 범위는 선수 출생 연도 검사(1900~2100)와 같고, 그 밖의 날짜는 이 서비스에서
 * 뜻이 없다. 2026-10-15 전에는 검사가 없어 `asOf=+999999999-12-31` 같은 값이 날짜 계산의 DateTimeException이나
 * PostgreSQL의 범위 오류로 500이 되었다.
 */
/** 선수 시간대의 기본값. V3__athlete_time_zone.sql의 열 기본값과 같다. */
const val DEFAULT_TIME_ZONE = "Asia/Seoul"

/**
 * 시간대 이름(IANA 이름 'Asia/Seoul', 'UTC', 또는 '+09:00' 같은 고정 오프셋)을 [java.time.ZoneId]로 바꾼다. 비었거나
 * 64자를 넘거나 Java가 모르는 이름이면 [BadRequestException](400). [name]은 오류 메시지에 쓰는 파라미터 이름이다.
 */
fun parseTimeZone(text: String, name: String = "timeZone"): java.time.ZoneId {
    if (text.isBlank() || text.length > 64) throw BadRequestException("'$name' must be a time zone such as Asia/Seoul")
    return try {
        java.time.ZoneId.of(text)
    } catch (e: java.time.DateTimeException) {
        throw BadRequestException("'$name' is not a known time zone: $text")
    }
}

fun requireReasonableDate(date: java.time.LocalDate, name: String) {
    if (date.year !in 1900..2100) throw BadRequestException("'$name' must be between 1900-01-01 and 2100-12-31")
}
