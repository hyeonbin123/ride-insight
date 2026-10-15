// [파일 역할] 서버 API의 요청·응답 JSON 모양을 TypeScript 타입으로 옮긴 파일. client.ts가 응답에 이 타입을
// 붙이고, 화면 컴포넌트는 이 타입으로 필드를 안전하게 쓴다.
// [주의] 서버 Dtos.kt를 손으로 따라 쓴 것이라 자동으로 맞춰지지 않는다. 서버는 null 필드를 응답에서
// 빼므로(Jackson non_null 설정) 값이 없을 수 있는 필드는 ?(선택)로 둔다. 날짜·시각은 JSON에서 문자열(ISO
// 형식)로 온다.

// server/src/main/kotlin/com/rideinsight/api/Dtos.kt를 그대로 옮겨 적은 것이다.

// FTP 이력 한 줄: 적용 시작일, FTP(W), 출처(manual 직접 입력, import OpenData 가져오기, estimate 추정).
export interface Threshold {
  effectiveFrom: string; // ISO 형식 날짜
  ftpWatts: number;
  source: string;
}

// 선수 + 오늘 기준 유효한 FTP(currentThreshold, 등록된 FTP가 없으면 없음). 상단 메뉴바와 선수 페이지가 쓴다.
export interface Athlete {
  id: string;
  name: string;
  gender?: string;
  birthYear?: number;
  weightKg?: number;
  createdAt: string;
  currentThreshold?: Threshold;
  // 현지 날짜를 셀 시간대(IANA 이름, 기본 Asia/Seoul). 서버는 PMC 날짜·FTP 적용일·기준일을 이 시간대로 센다.
  timeZone: string;
}

// 선수 생성 요청. 서버 검증: 이름 1~100자, 성별 M/F, 출생 연도 1900~2100, FTP 50~1000 W(docs/api.md). ftp를
// 주면 서버가 1990-01-01부터 적용되는 FTP 이력으로 등록해, 과거 날짜의 라이딩을 올려도 IF·TSS가
// 계산된다(AthleteService.create).
export interface CreateAthleteRequest {
  name: string;
  gender?: string;
  birthYear?: number;
  weightKg?: number;
  ftp?: number;
  timeZone?: string;
}

// 활동 하나의 지표: 계산에 쓴 FTP(ftpUsed), NP, IF, TSS, 평균·최대 파워, 일(kJ), 16구간 MMP. FTP가 없으면
// IF·TSS가 없다. mmp의 키는 GoldenCheetah 메타데이터 이름(예: 20m_critical_power)이고, 라이딩이 구간보다
// 짧으면 값이 null이다.
export interface Metrics {
  ftpUsed?: number;
  normalizedPower?: number;
  intensityFactor?: number;
  tss?: number;
  averagePower?: number;
  maxPower?: number;
  workKj?: number;
  mmp: Record<string, number | null>;
}

// 활동 요약. recIntSecs는 최빈 간격으로 추정한 기록 간격(초), intervalShare는 그 간격의 비율(0.9 미만이면
// 화면이 '타임스탬프 흔들림'으로 표시). crcValid=false는 CRC가 틀렸지만 저장된 FIT(거절하지 않고 표시).
// hasPower가 false면 metrics가 없다. device는 FIT file_id의 제조사·제품 번호(숫자 코드).
export interface Activity {
  id: string;
  athleteId: string;
  startedAt: string;
  source: string;
  sourceName?: string;
  sport?: string;
  recIntSecs?: number;
  intervalShare?: number;
  sampleCount: number;
  durationSecs?: number;
  device: { manufacturer?: number; product?: number };
  crcValid?: boolean;
  hasPower: boolean;
  powerMissing: number;
  metrics?: Metrics;
}

// 목록 한 페이지와 다음 페이지 커서(nextBefore: 이 페이지 마지막 활동의 시작 시각, 마지막 페이지면 없음).
export interface ActivityList {
  items: Activity[];
  nextBefore?: string;
}

// 시계열. every는 서버가 실제로 쓴 간격(초, 응답이 20,000점을 넘지 않도록 서버가 키울 수 있다). 시리즈는
// secs와 같은 길이의 평행 배열이고, 센서가 없으면 그 시리즈 자체가 빠지며, 중간에 빠진 샘플은 null이다(차트가
// 선을 끊어 그린다).
export interface Samples {
  every: number;
  secs: number[];
  power?: (number | null)[];
  heartRate?: (number | null)[];
  cadence?: (number | null)[];
  distanceM?: (number | null)[];
  altitudeM?: (number | null)[];
}

// 파워 커브의 한 점: 구간 길이(초)와 그 구간의 최대 평균 파워(W).
export interface CurvePoint {
  secs: number;
  watts: number;
}

// 파워 커브 응답. points는 그래프용 곡선 점들, standard는 16개 표준 구간 값(키는 GoldenCheetah 이름). 활동
// 커브와 기간 최고 커브가 같은 모양을 쓴다.
export interface PowerCurve {
  points: CurvePoint[];
  standard: Record<string, number | null>;
}

// PMC 하루치: 그날 TSS 합, CTL(42일 지수 가중 평균, 체력), ATL(7일, 피로), TSB(어제 CTL − 어제 ATL, 컨디션).
// date는 UTC 날짜(yyyy-MM-dd).
export interface TrainingLoadDay {
  date: string;
  tss: number;
  ctl: number;
  atl: number;
  tsb: number;
}

export interface TrainingLoadResponse {
  days: TrainingLoadDay[];
}

// 서버 오류 응답 본문 { error: 코드, message: 설명 } (docs/api.md). client.ts의 ApiRequestError가 읽는다.
export interface ApiError {
  error: string;
  message: string;
}

// FTP 추정 응답(GET /athletes/{id}/ftp-estimate). 3단계 측정에서 모델이 채택되지 않아 서비스는 규칙 R1(20분
// 최대 × 0.95)을 ftpWatts로 돌려준다. rules에는 R1·R2(60분 최대)·R3(CP 모델) 값, ridesInWindow는 기간 안 파워
// 라이딩 수, note에는 이 규칙의 측정 오차(dev·test MAE, 편향)가 문장으로 들어 있다(docs/experiments.md 3단계
// 서비스 반영). 대시보드의 FTP 추정 카드가 쓴다.
export interface FtpEstimate {
  method: string;
  ftpWatts?: number;
  rules: Record<string, number | null>;
  criticalPowerWPrimeKj?: number;
  windowsUsed: number;
  ridesInWindow: number;
  best20m?: number;
  best60m?: number;
  weeks: number;
  note: string;
  // 2026-10-15 추가: note의 측정 오차를 숫자로. 화면은 이 값으로 한국어 안내를 만든다(format.ts의 ftpEstimateNote).
  devMaeWatts?: number;
  testMaeWatts?: number;
  testBiasWatts?: number;
}
