# 개발 계획 (2026-10-10, 개발 시작 전)

## 목표
사이클링 파워미터 데이터(FIT 파일, 공개 데이터셋)를 받아 **훈련 지표**(NP·IF·TSS, 파워 커브, 훈련 부하 CTL/ATL/TSB)를 계산하고 **FTP(1시간 유지 가능 파워)를 추정**하는 웹 서비스를 만든다. 서버는 Kotlin(Spring Boot), FTP 추정 모델은 Python으로 학습해 서버에서 서빙하고, 화면은 React로 만든다.

지표 구현이 정확한지, 어떤 FTP 추정 방법이 실제 선수 데이터에서 가장 잘 맞는지, 기기마다 다른 FIT 파일을 얼마나 안전하게 받아들이는지를 **미리 정한 규칙으로** 잰다. 그리고 AI 코딩 도구가 만든 코드를 어떤 절차로 검증했는지를 기능마다 기록으로 남긴다(`docs/ai-verification.md`).

## 측정으로 답할 질문
1. **지표 정확성**: 직접 구현한 NP·IF·TSS·N초 최대 평균 파워(MMP)는 같은 초 단위 데이터로 GoldenCheetah가 계산해 둔 값과 얼마나 일치하는가. 어긋나는 라이딩은 왜 어긋나는가(기록 간격, 결측, 센서 튐)
2. **FTP 추정**: 최근 몇 주의 라이딩만 보고 선수가 설정해 둔 FTP(`cp_setting`)를 얼마나 가깝게 맞히는가. 전통 규칙(20분 최대 평균 × 0.95, Critical Power 모델)과 학습한 모델 중 어느 것이 **처음 보는 선수**에게서 더 잘 맞는가
3. **라벨의 질**: 선수가 설정한 FTP는 정답이 아니다(안 바꾸고 두는 사람이 많다). FTP를 자주 갱신하는 선수와 그렇지 않은 선수에서 결과가 어떻게 다른가. 갱신 직후 구간만 라벨로 쓰면 결과가 달라지는가
4. **기록 조건 강건성**: 5초 간격 기록, 불규칙 간격(1.26초 등), 파워 결측이 섞인 라이딩에서 지표와 추정이 얼마나 흔들리는가. 1초로 다시 맞추는 전처리가 필요한가
5. **FIT 수용성**: Garmin·Wahoo·Coros 등 서로 다른 기기의 FIT 파일과 손상된 파일(헤더 손상, 중간에 끊긴 파일)을 서버가 어떻게 처리하는가. 거부해야 할 것을 거부하고 받아야 할 것을 받는가
6. **서비스 품질**: 라이딩 업로드부터 지표 응답까지 지연 시간, 동시 업로드 부하, DAST(OWASP ZAP) 스캔 결과

## 데이터

### GoldenCheetah OpenData (주 데이터)
- 출처 OSF https://osf.io/6hfpz (DOI 10.17605/OSF.IO/6HFPZ), 라이선스 **CC0 1.0**. GoldenCheetah 사용자가 2018년부터 공유한 익명 데이터. GPS·개인정보 없음
- 선수 1명 = zip 1개. `{id}.json`에 선수 정보(성별, 출생 연도)와 라이딩별 메타데이터(날짜, 종목, 메트릭 277종), 라이딩별 CSV(`secs,km,power,hr,cad,alt`)
- 메트릭 중 이 프로젝트에서 쓰는 것: `cp_setting`(그 날짜에 선수가 설정해 둔 CP/FTP → **라벨**), `coggan_np`·`coggan_if`·`coggan_tss`, `1s~90m_critical_power`(16개 구간 MMP), `power_mmp`, `average_power`, `workout_time`, `athlete_weight`
- 2026-10-10 선수 1명(라이딩 731개) 확인: 파워 있는 라이딩 71%, 기록 간격 1초 65%·5초 13%·1.26초 9%
- 전체(OSF API로 확인, 2026-10-10): 선수 6,613명, 115.6 GB. 파일 크기 중앙값 8.5 MB, 90%가 47 MB 이하
- **표본**: 80 MB 이하 파일 중 SHA-256(`sample:` + id) 순서로 앞 800명(12.4 GB, 분할 train 475 / dev 167 / test 158). 인벤토리를 만든 뒤 파워 라이딩 100개 이상인 선수만 쓴다. 매니페스트(`manifests/opendata_sample.json`)를 커밋한다
- 데이터는 재배포하지 않는다. 다운로드 스크립트, 매니페스트, 집계 수치만 커밋한다

### 샘플 FIT 파일
- python-fitparse(MIT) 테스트 파일: Garmin Edge 500/810(파워미터 Vector)/820, Wahoo ELEMNT, Coros Pace 2(필드 정렬 오류), 인도어 트레이너, 헤더 손상, 중간에 끊긴 파일. 파서 테스트와 5번 질문에 쓴다
- **FIT 디코더는 직접 쓴다.** Garmin FIT SDK(Maven Central `com.garmin:fit`)의 FIT Protocol License는 "내부 업무 목적" 사용과 기밀 유지를 조건으로 하고 재배포를 제한해(2026-10-10 LICENSE.txt 확인), 공개 저장소의 의존성으로 두기에 불확실하다. FIT 프로토콜(헤더, 정의·데이터 메시지, 압축 타임스탬프 헤더, CRC-16, 개발자 필드)과 필요한 메시지(file_id 0, record 20, session 18, event 21)의 필드 번호는 공개 문서와 python-fitparse(MIT)의 프로필을 참고해 Kotlin으로 구현하고, 샘플 FIT 파일에서 python-fitparse의 디코딩 결과와 대조한다(5번 질문과 함께 `experiments.md`에 규칙을 적는다)

### 데이터 나누기 (선수 단위)
| 세트 | 비율 | 용도 |
|---|---|---|
| train | 60% | 모델 학습 |
| dev | 20% | 후보 고르기, 전처리 결정, 실패 분석 |
| test | 20% | 단계가 끝날 때 정해 둔 것만 한 번 |

같은 선수의 라이딩은 한 세트에만 들어간다. 분할은 선수 id의 SHA-256 앞 바이트로 정하고 테스트로 확인한다. 1번 질문(지표 정확성)은 학습이 아니라 구현 검증이므로 train 선수로 확인하고 dev·test는 쓰지 않는다.

## 지표 정의 (GoldenCheetah 구현과 맞춤)
- **NP**: 30초 이동 평균(창 크기 = 30 / 기록 간격)을 4제곱해 평균한 뒤 4제곱근. GoldenCheetah는 처음 30초를 빼지 않고 0으로 채운 버퍼에서 시작한다. 이 구현을 그대로 따른다(1번 질문의 대조가 목적이므로)
- **IF** = NP / FTP, **TSS** = (초 × NP × IF) / (FTP × 3600) × 100. FTP는 그 날짜의 `cp_setting`
- **MMP(N초 최대 평균 파워)**: 1, 5, 10, 15, 20, 30초, 1, 2, 3, 5, 8, 10, 20, 30, 60, 90분. GoldenCheetah의 `findPeaks` 방식을 확인해 0단계에서 확정한다(기록 간격 처리, 창보다 짧은 라이딩)
- **훈련 부하**: CTL = 42일 지수 가중 평균, ATL = 7일, TSB = 어제 CTL − 어제 ATL (Coggan PMC 표준)
- **FTP 추정 후보** (2번 질문)
  - R1: 최근 6주 20분 MMP × 0.95
  - R2: 최근 6주 60분 MMP
  - R3: Critical Power 2변수 모델(Monod–Scherrer, 3~20분 MMP 선형 적합)의 CP
  - R4: CP 3변수 모델(Morton)의 CP
  - M1: 학습 모델(LightGBM 또는 선형): 입력은 최근 6주의 MMP 16개 구간, 라이딩 수·총 시간·TSS 합, 체중(있을 때), 성별·연령대. 출력은 FTP. 선수 단위 교차 검증
  - 라벨: 창의 마지막 날 기준 `cp_setting`. 2026-10-10 1단계 점검에서 GC가 IF·TSS에 쓴 임계값(`coggan_np / coggan_if`, 존 설정의 FTP)이 `cp_setting`(CP)과 다른 라이딩이 한 선수에서 20%였다. 그래서 라벨 후보를 둘로 둔다: **CP 라벨**(`cp_setting`)과 **FTP 라벨**(`coggan_np / coggan_if`). 어느 것을 주 라벨로 쓸지는 3단계 규칙에 측정 전에 적는다. 3번 질문을 위해 "창이 끝난 뒤 4주 안에 라벨이 바뀐 경우"만 모은 부분 집합도 따로 잰다
  - 지표: MAE(W), MAPE, 체중당(W/kg) MAE. 불확실성은 선수 단위 부트스트랩 95% 구간, 비교는 같은 선수끼리 짝지은 부트스트랩

## 단계
| 단계 | 내용 | 산출물 |
|---|---|---|
| 0 | 뼈대: 서버·ml·web 폴더, 데이터 표본 매니페스트, 다운로드 스크립트, CI, 계획·규칙 문서 | 첫 커밋, `docs/experiments.md` 1단계 규칙 |
| 1 | 지표 구현(Kotlin 순수 함수) + OpenData 대조(1·4번 질문) | 파리티 리포트, 어긋난 라이딩 분석 |
| 2 | 저장·API: PostgreSQL 스키마(선수, 라이딩, 샘플, 지표), FIT 업로드·파싱, OpenData 가져오기, REST API, 훈련 부하 | API 문서, 통합 테스트(Testcontainers) |
| 3 | FTP 추정: 규칙 R1~R4(Kotlin)와 모델 M1(Python) 비교(2·3번 질문). 채택한 것을 서버에 넣는다(모델이면 ONNX로 내보내 JVM에서 추론) | 비교 리포트, 추정 API |
| 4 | 화면(React + TypeScript + D3): 업로드, 라이딩 목록·상세(파워·심박 그래프), 파워 커브, 훈련 부하(PMC), FTP 추정 카드와 근거 | 동작하는 웹 |
| 5 | FIT 수용성·보안·부하(5·6번 질문): 손상 파일 처리, OWASP ZAP, 동시 업로드, Docker Compose 한 번에 실행 | 보안·부하 리포트 |
| 6 | README, 그림, 한계, `docs/ai-verification.md` 정리 | 최종 문서 |

각 단계는 "규칙 커밋 → 구현 → dev 확인 → 봉인 테스트 한 번 → 결과 커밋" 순서다. 측정이 없는 단계(2, 4)는 완료 기준(테스트·명령)을 먼저 적는다.

### 진행 기록 (2026-10-10)
- 0~5단계를 하루에 모두 쟀다. 결과는 `docs/experiments.md`(1·3·5단계)와 `docs/api.md`(2단계 완료 기준), 화면은 4단계로 `web/`
- 3단계에서 모델은 채택되지 않았다(H5 판정 불가). 서비스는 규칙 R1을 쓰고 측정한 오차를 화면에 보여 준다. 모델을 Kotlin에서 직접 추론하는 코드는 준비해 두었다
- 계획과 달라진 것: Garmin FIT SDK 대신 직접 쓴 디코더(라이선스), R4(CP 3변수)는 돌리지 않음, 인증은 넣지 않음(로컬 데모 결정), OpenData 매핑 규칙(UTC↔현지 시각)과 수리 규칙은 데이터를 본 뒤 추가
- (2026-10-29 덧붙임) 위 목록에 빠졌던 것: M1은 '선수 단위 교차 검증' 대신 선수 단위 train/dev/test 분할 한 번으로 정했다. 매개변수를 측정 전에 고정해(튜닝 없음) train으로 한 번 맞추고, dev MAE로 채택 후보를 고른 뒤 봉인 test를 한 번 쟀다(`docs/experiments.md` 3단계 '후보'·'지표와 판정'). 입력 특징도 위 M1 줄이 아니라 측정 전에 등록한 그 규칙의 목록을 따랐다(규칙과 구현의 차이는 같은 문서 규칙 변경 6·15)

## AI 검증 기록 (`docs/ai-verification.md`)
이 프로젝트는 Claude Code와 Codex로 개발한다. 기능(또는 커밋 단위)마다 다음을 남긴다.
1. AI가 만든 것(어느 도구, 어떤 지시)
2. 검증 방법: 단위·통합 테스트, 예외 입력(빈 파일, 손상 파일, 음수·3000W 넘는 파워, 기록 간격 불규칙), 보안(입력 크기 제한, 경로, 인증, DAST), 데이터 정확성(GoldenCheetah 값 대조, 손으로 계산한 작은 예제)
3. 검증에서 잡은 AI의 실수와 고친 커밋
4. 사람이 내린 판단(요구사항, 판정 기준, 채택 결정)

만든 쪽과 검토하는 쪽을 나눈다: Claude가 만든 것은 Codex가, Codex가 만든 것은 Claude가 검토한다.

## 구조
```
ride-insight/
  server/   Kotlin Spring Boot (com.rideinsight.metrics: 순수 지표 계산, .fit: FIT 디코딩, .api, .storage)
  ml/       Python: OpenData 표본 다운로드, 전처리, FTP 모델 학습·평가, 파리티 리포트
  web/      React + TypeScript (Vite), D3
  docs/     plan.md, experiments.md, ai-verification.md, figures/
  manifests/ 표본 선수 목록, 분할
  reports/  측정 결과(JSON·CSV)
  compose.yaml  PostgreSQL + 서버 + 웹
```

## 쓰지 않는 것 / 한계
- Strava·Garmin Connect 연동: 계정이 없어 하지 않는다. FIT 업로드로 대신한다
- 실사용자 운영: 공개 데모를 올리더라도 실사용자 수치는 주장하지 않는다
- 라벨(`cp_setting`)은 선수가 입력한 값이라 정답이 아니다. 결과는 "선수 설정값과의 차이"로만 말한다
- OpenData는 GoldenCheetah 사용자(훈련에 진지한 아마추어·선수 위주)의 데이터라 일반 사용자와 분포가 다를 수 있다
