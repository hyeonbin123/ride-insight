-- [파일 역할] 선수마다 시간대(IANA 이름, 예: Asia/Seoul)를 둔다. 훈련 부하(PMC)의 날짜 묶음, 활동 날짜에 적용할 FTP,
-- 기간 커브·FTP 추정의 기준일을 이 시간대의 현지 날짜로 센다(2026-10-15 전에는 모두 UTC 날짜였다).
-- [이유] 한국(UTC+9)의 새벽·아침 라이딩(00:00~08:59)이 UTC로는 전날이라 PMC의 전날에 쌓이고, 그날부터 바꾼 FTP 대신
-- 전날 FTP로 TSS가 계산될 수 있었다. 기본값 Asia/Seoul은 이 서비스의 주 사용자를 기준으로 한 일상적 선택이고, 선수
-- 생성·변경 API와 조회 요청의 tz 파라미터로 바꿀 수 있다(docs/api.md).
-- [기존 행] 이미 있는 선수는 기본값(Asia/Seoul)이 된다. 저장된 TSS는 다음 FTP 변경이나 시간대 변경 때 새 기준으로 다시
-- 계산된다.
alter table athlete add column time_zone text not null default 'Asia/Seoul';
