package com.rideinsight.fit

// [파일 역할] FitDecoder가 만든 일반 메시지 목록(FitFile)에서 이 프로젝트가 쓰는 값만 골라, 단위를 맞춘
// Kotlin 객체(파일 id, 세션 요약, 종목, record 시계열)로 바꾸는 계층이다.
// [설계 이유] FitDecoder는 프로토콜만 알고 메시지의 의미는 모른다(필드 번호 → 원시 정수). 어느 필드가
// 파워인지, 배율·오프셋이 얼마인지는 FitProfile 번호표로 여기서 붙여, 바이트 해석과 프로필 해석을 나눴다.
// [연결] ActivityService.ingestFit이 업로드 바이트를 FitActivity.decode로 읽는다. FitDecoderTest가
// python-fitparse 결과와 record·세션 값을 대조하고, ApiIntegrationTest가 기기가 세션에 적은 NP·TSS·IF와 우리
// 계산을 나란히 출력한다.

/**
 * `record` 메시지 하나(한 시점의 측정값)를 이 프로젝트가 쓰는 값으로 줄인 것: 시각(Unix 초), 파워(W),
 * 심박(bpm), 케이던스(rpm), 누적 거리(m), 속도(m/s), 고도(m). null은 센서가 없거나 무효 값이라는 뜻이다.
 */
class FitRecordSample(
    val timestampUnix: Long,
    val power: Int?,
    val heartRate: Int?,
    val cadence: Int?,
    val distanceMeters: Double?,
    val speedMetersPerSecond: Double?,
    val altitudeMeters: Double?,
)

// session 메시지(라이딩 전체 요약). 기기가 직접 계산해 둔 평균·최대 파워, NP, TSS, IF, FTP(threshold_power)
// 등을 담는다. 서비스는 이 값들을 저장하지 않고 우리 계산을 쓰며, 종목 판단과 테스트의 기기 값
// 비교(docs/api.md 완료 기준 3)에만 쓴다.
class FitSessionSummary(
    val sport: Long?,
    val subSport: Long?,
    val startTimeUnix: Long?,
    val totalElapsedSecs: Double?,
    val totalTimerSecs: Double?,
    val totalDistanceMeters: Double?,
    val avgPower: Int?,
    val maxPower: Int?,
    val normalizedPower: Int?,
    val trainingStressScore: Double?,
    val intensityFactor: Double?,
    val thresholdPower: Int?,
    val totalAscentMeters: Int?,
)

// file_id 메시지: 파일 종류(4 = activity), 제조사·제품 번호, 일련번호, 만든 시각. 제조사·제품은 활동의
// device로 저장된다.
class FitFileId(
    val type: Long?,
    val manufacturer: Long?,
    val product: Long?,
    val serialNumber: Long?,
    val timeCreatedUnix: Long?,
)

/**
 * 활동 파일 하나: 파일 id, 세션들, 파일 순서 그대로의 record 시계열.
 * crcValid: 파일 CRC가 맞는지(틀려도 거절하지 않고 표시만 한다, 5단계 H8). developerFieldDefinitions: 개발자
 * 필드를 가진 정의 메시지 수(보고용). sports: sport 메시지들의 종목 코드.
 */
class FitActivity(
    val fileId: FitFileId?,
    val sessions: List<FitSessionSummary>,
    val sports: List<Long?>,
    val records: List<FitRecordSample>,
    val crcValid: Boolean,
    val developerFieldDefinitions: Int,
) {
    /**
     * 종목: 첫 세션의 sport가 우선이고, 세션이 없으면 첫 `sport` 메시지를 본다. ActivityService가 이 값으로
     * 러닝·수영 등을 거절한다.
     */
    val sport: Long? get() = sessions.firstOrNull()?.sport ?: sports.firstOrNull()

    companion object {
        // FitFile → FitActivity 변환. 시각 필드는 FIT 기준 시각(1989-12-31) 이후 초라 FIT_EPOCH_OFFSET을 더해
        // Unix 초로 바꾸고, 정수 필드에는 FitProfile 주석에 적힌 배율을 적용한다(scaled = raw / scale −
        // offset). file_id는 첫 메시지만 쓴다.
        fun from(file: FitFile): FitActivity {
            val fileId = file.messages(FitProfile.MSG_FILE_ID).firstOrNull()?.let {
                FitFileId(
                    type = it.int(FitProfile.FileId.TYPE),
                    manufacturer = it.int(FitProfile.FileId.MANUFACTURER),
                    product = it.int(FitProfile.FileId.PRODUCT),
                    serialNumber = it.int(FitProfile.FileId.SERIAL_NUMBER),
                    timeCreatedUnix = it.int(FitProfile.FileId.TIME_CREATED)?.plus(FitDecoder.FIT_EPOCH_OFFSET),
                )
            }
            // 세션 메시지는 여러 개일 수 있어 모두 읽어 둔다.
            val sessions = file.messages(FitProfile.MSG_SESSION).map { s ->
                FitSessionSummary(
                    sport = s.int(FitProfile.Session.SPORT),
                    subSport = s.int(FitProfile.Session.SUB_SPORT),
                    startTimeUnix = s.int(FitProfile.Session.START_TIME)?.plus(FitDecoder.FIT_EPOCH_OFFSET),
                    // FIT 프로필 배율: 시간은 ms(÷1000), 거리는 cm(÷100), TSS는 ÷10, IF는 ÷1000.
                    totalElapsedSecs = s.scaled(FitProfile.Session.TOTAL_ELAPSED_TIME, 1000.0),
                    totalTimerSecs = s.scaled(FitProfile.Session.TOTAL_TIMER_TIME, 1000.0),
                    totalDistanceMeters = s.scaled(FitProfile.Session.TOTAL_DISTANCE, 100.0),
                    avgPower = s.int(FitProfile.Session.AVG_POWER)?.toInt(),
                    maxPower = s.int(FitProfile.Session.MAX_POWER)?.toInt(),
                    normalizedPower = s.int(FitProfile.Session.NORMALIZED_POWER)?.toInt(),
                    trainingStressScore = s.scaled(FitProfile.Session.TRAINING_STRESS_SCORE, 10.0),
                    intensityFactor = s.scaled(FitProfile.Session.INTENSITY_FACTOR, 1000.0),
                    thresholdPower = s.int(FitProfile.Session.THRESHOLD_POWER)?.toInt(),
                    totalAscentMeters = s.int(FitProfile.Session.TOTAL_ASCENT)?.toInt(),
                )
            }
            val sports = file.messages(FitProfile.MSG_SPORT).map { it.int(FitProfile.Sport.SPORT) }
            val records = ArrayList<FitRecordSample>()
            // record 메시지마다 샘플 하나. 파일 순서 그대로 두고 정렬하지 않는다(시각이 거꾸로 가는 파일은
            // ActivityService가 400으로 거절한다). timestamp가 없는 record는 시간축에 놓을 수 없어 건너뛴다.
            for (r in file.messages(FitProfile.MSG_RECORD)) {
                val ts = r.timestampUnix ?: continue // timestamp가 없으면 시간축에 놓을 수 없다
                records.add(
                    FitRecordSample(
                        timestampUnix = ts,
                        power = r.int(FitProfile.Record.POWER)?.toInt(),
                        heartRate = r.int(FitProfile.Record.HEART_RATE)?.toInt(),
                        cadence = r.int(FitProfile.Record.CADENCE)?.toInt(),
                        distanceMeters = r.scaled(FitProfile.Record.DISTANCE, 100.0),
                        // 속도·고도는 32비트 enhanced 필드(범위가 더 넓음)가 있으면 그것을, 없으면 16비트
                        // 기본 필드를 쓴다. 고도는 raw / 5 − 500 m.
                        speedMetersPerSecond = r.scaled(FitProfile.Record.ENHANCED_SPEED, 1000.0)
                            ?: r.scaled(FitProfile.Record.SPEED, 1000.0),
                        altitudeMeters = r.scaled(FitProfile.Record.ENHANCED_ALTITUDE, 5.0, 500.0)
                            ?: r.scaled(FitProfile.Record.ALTITUDE, 5.0, 500.0),
                    ),
                )
            }
            return FitActivity(fileId, sessions, sports, records, file.crcValid, file.developerFieldDefinitions)
        }

        // 바이트 배열 → FitActivity를 한 번에. 구조 오류는 FitDecodeException(API에서 400 invalid_fit)으로
        // 올라간다.
        fun decode(bytes: ByteArray): FitActivity = from(FitDecoder.decode(bytes))
    }
}
