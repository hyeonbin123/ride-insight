# 2단계: 저장과 API (2026-10-10 완료 기준, 구현 전)

측정이 없는 단계라 완료 기준을 먼저 적는다. 서버는 Kotlin + Spring Boot, 저장은 PostgreSQL(Flyway 마이그레이션), SQL은 `JdbcClient`로 직접 쓴다(JPA는 쓰지 않는다. 초 단위 시계열을 배열 컬럼으로 저장하는 데 ORM이 방해가 된다).

## 저장 모델
| 테이블 | 내용 |
|---|---|
| `athlete` | id(uuid), name, gender, birth_year, weight_kg, created_at |
| `athlete_threshold` | athlete_id, effective_from(date), ftp_watts, source(`manual` / `estimate`). 그 날짜의 FTP는 effective_from ≤ 날짜인 가장 최근 행 |
| `activity` | id(uuid), athlete_id, started_at(timestamptz), source(`fit` / `opendata`), source_name, sport, rec_int_secs, sample_count, duration_secs, device(manufacturer, product), crc_valid, jitter(최빈 간격 비율), power_missing, created_at. 같은 선수·같은 시작 시각은 유일(중복 업로드 거절) |
| `activity_samples` | activity_id, secs real[], power smallint[], heart_rate smallint[], cadence smallint[], distance_m real[], altitude_m real[]. 활동당 한 행. 결측은 NULL 원소 |
| `activity_metrics` | activity_id, ftp_used, np, intensity, tss, average_power, max_power, mmp jsonb(16구간), work_kj, computed_at. 지표 코드가 바뀌면 다시 계산해 덮어쓴다(`metrics_version`) |

## API (모두 `/api` 아래, JSON)
| 메서드·경로 | 동작 | 완료 기준 |
|---|---|---|
| `POST /athletes` | 선수 생성 {name, gender?, birthYear?, weightKg?, ftp?} | 201 + id. 이름 1~100자, FTP 50~1000 W 아니면 400 |
| `GET /athletes/{id}` | 선수 + 현재 FTP | 없으면 404 |
| `PUT /athletes/{id}/threshold` | FTP 등록 {effectiveFrom, ftpWatts} | 그 날짜 이후 활동의 TSS를 다시 계산한다 |
| `POST /athletes/{id}/activities` | FIT 업로드(multipart, 파일 1개) | 201 + 활동 요약(지표 포함). 디코딩 실패 400(`FitDecodeException` 메시지), 종목이 러닝·수영·걷기·하이킹이면 422(그 외 종목과 종목 미상은 저장), 파워 없는 파일은 저장하되 지표 null, 같은 시작 시각이면 409, 10 MB 초과 413 |
| `GET /athletes/{id}/activities?before&limit` | 활동 목록(지표 포함, 최신순) | 기본 50개(최대 200), `before`(시작 시각) 커서. 응답의 `nextBefore`를 다음 요청에 넣는다 (처음 적었던 `from&to&cursor`는 구현에 맞춰 고침, Codex 검토 2026-10-10) |
| `GET /activities/{id}` | 활동 상세 + 지표 | |
| `GET /activities/{id}/samples?every=N` | 시계열(경과 시간 N초 구간마다 첫 샘플 한 점) | 기본 every=1, 응답이 20,000점을 넘지 않도록 N을 자동으로 키운다 |
| `GET /activities/{id}/power-curve` | 16구간 MMP + 전 구간(1초 단위 1~최대) 곡선 | 곡선은 로그 간격으로 ≤ 300점 |
| `GET /athletes/{id}/training-load?from&to` | 날짜별 TSS 합, CTL, ATL, TSB | CTL 42일·ATL 7일 지수 가중, 활동 없는 날은 TSS 0으로 감쇠. 부하는 선수의 첫 활동부터 누적해 `from` 이전 값을 워밍업으로 쓴다 |
| `GET /athletes/{id}/power-curve?weeks=6` | 기간 최대 파워 커브(활동별 MMP의 구간별 최대) | 3단계 FTP 추정의 입력 |
| `DELETE /activities/{id}` | 삭제 | 204 |

- 인증은 2단계에서 넣지 않는다(로컬 데모). 5단계 보안 점검에서 선수 토큰(헤더)을 넣을지 정한다
- 오류 응답은 `{ "error": "<코드>", "message": "<설명>" }`

## OpenData 가져오기
`./gradlew importOpenData --args="--zip <선수 zip> --index <ride index json> --name <이름> [--label cp|ftp]"`: 짝지어진 파워 라이딩을 활동으로 넣고 `cp_setting`(또는 FTP 라벨) 변화를 `athlete_threshold`에 넣는다. 화면 데모와 3단계 입력에 쓴다.

## 완료 기준 (전부 통과해야 2단계 끝)
1. `./gradlew test`: 단위 테스트 + Testcontainers 통합 테스트(PostgreSQL 컨테이너)에서 위 API를 모두 호출하는 테스트가 통과한다
2. 샘플 FIT 9개를 업로드하는 통합 테스트: 사이클링 5개 저장, 러닝 1개 422, 손상 2개 400, Coros(개발자 필드 정렬 오류, fitparse도 거절)는 저장 또는 400 둘 다 허용하되 500은 아님
3. 업로드한 Edge810 파일의 NP·TSS·MMP가 `com.rideinsight.metrics` 단위 계산과 같고, 세션 메시지의 `normalized_power`(301)·`training_stress_score`(118.7, FTP 315)와의 차이를 테스트가 기록한다(기기 계산과의 차이는 판정하지 않고 보고만)
4. OpenData 선수 1명을 가져온 뒤 `training-load`가 날짜별 CTL/ATL/TSB를 돌려주고, 손으로 계산한 3일 예제와 일치하는 단위 테스트가 있다
5. `docker compose up`으로 DB + 서버가 뜨고 `GET /actuator/health`가 UP — **확인(2026-10-10)**: 루트 `compose.yaml`(postgres:16 + 서버 이미지 529 MB + nginx 웹 74 MB)을 `docker compose up --build`로 띄워 health UP, 웹 200, nginx 프록시를 거친 선수 생성과 FIT 업로드 201을 확인하고 내렸다
6. `docs/ai-verification.md`에 2단계 항목(AI가 만든 것, 검증, 잡은 실수)을 적는다
