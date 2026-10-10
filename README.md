# ride-insight

사이클링 파워미터 데이터(FIT 파일)를 받아 훈련 지표를 계산하고 FTP를 추정하는 웹 서비스다. 서버는 Kotlin(Spring Boot), FTP 추정 모델은 Python으로 학습하고, 화면은 React로 만든다.

지표 구현의 정확성, FTP 추정 방법의 성능, 기기별 FIT 파일의 수용성을 **미리 정한 규칙으로** 잰다. 계획은 [docs/plan.md](docs/plan.md), 측정 규칙과 결과는 [docs/experiments.md](docs/experiments.md), AI 코딩 도구가 만든 코드를 어떻게 검증했는지는 [docs/ai-verification.md](docs/ai-verification.md)에 있다. 규칙은 재기 전에 커밋한다.

## 구조
| 폴더 | 내용 |
|---|---|
| `server/` | Kotlin 2.3 + Spring Boot 4.1 (JDK 21, PostgreSQL). 지표 계산은 `com.rideinsight.metrics` 순수 함수 |
| `ml/` | Python 3.11 (uv). OpenData 표본 추출·다운로드·인벤토리, 전처리, FTP 모델 학습·평가 |
| `web/` | React 19 + TypeScript (Vite), D3 |
| `manifests/` | 데이터 표본과 분할(선수 id 목록) |
| `reports/` | 측정 결과 |

## 데이터
- **GoldenCheetah OpenData** — [OSF osf.io/6hfpz](https://osf.io/6hfpz) (DOI 10.17605/OSF.IO/6HFPZ), **CC0 1.0**. GoldenCheetah 사용자 6,613명이 공유한 익명 라이딩 데이터(초 단위 파워·심박·케이던스·거리·고도, GPS 없음). 이 저장소에는 데이터를 넣지 않고 표본 목록과 다운로드 스크립트만 둔다
- **샘플 FIT 파일** — [python-fitparse](https://github.com/dtcooper/python-fitparse) 테스트 파일(MIT). 파서 테스트용

```bash
cd ml
uv sync
uv run python -m ride_insight_ml.opendata download --sample ../manifests/opendata_sample.json --dest ../data/opendata
uv run python -m ride_insight_ml.fit_samples --dest ../data/samples/fit
```

## 실행·테스트
```bash
# 서버
cd server && ./gradlew test
# 모델
cd ml && uv run pytest && uv run ruff check .
# 화면
cd web && npm install && npm run lint && npm run build
```

진행 중인 프로젝트다. 결과는 단계가 끝날 때마다 이 문서에 추가한다.
