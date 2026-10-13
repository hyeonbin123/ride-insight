package com.rideinsight.service

// [파일 역할] 서비스 계층이 던지는 도메인 예외 세 가지. 서비스는 HTTP를 모르고 무슨 일이 일어났는지만 알리고,
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
