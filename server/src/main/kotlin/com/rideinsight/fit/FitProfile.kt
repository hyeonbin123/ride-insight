package com.rideinsight.fit

// [파일 역할] FIT 전역 프로필 중 이 프로젝트가 쓰는 부분의 번호표다: 메시지 번호, 필드 번호, 그리고 필드의
// 배율·오프셋(각 줄 끝 주석).
// [설계 이유] Garmin FIT SDK는 FIT Protocol License(내부 업무 목적, 기밀 유지, 재배포 제한)가 공개 저장소의
// 의존성으로 두기에 불확실해 쓰지 않았다(docs/plan.md, README). 그래서 번호는 공개 프로필과
// python-fitparse(MIT)가 가진 프로필에서 옮겨 적었고, 맞는지는 FitDecoderTest의 fitparse 대조로 확인한다.
// [연결] FitActivity가 필드를 꺼낼 때, FitDecoder가 timestamp(253)를 다룰 때, ActivityService가 종목을 판단할
// 때 쓴다. 지금 읽지 않는 메시지·필드(lap, event, activity, 위치, 온도 등)도 번호만 적어 두었다.

/**
 * FIT 전역 프로필 중 이 프로젝트에 필요한 부분: 메시지 번호, 필드 번호, 사이클링 활동에 쓰는 필드의
 * 배율·오프셋. 번호는 공개된 FIT 프로필(python-fitparse(MIT)에도 들어 있다)을 따랐고, Garmin SDK의 내용은
 * 하나도 넣지 않았다.
 */
object FitProfile {
    // 어느 메시지에서나 timestamp를 뜻하는 공통 필드 번호. 압축 타임스탬프 헤더의 기준 시각도 이 필드에서
    // 온다.
    const val FIELD_TIMESTAMP = 253

    // 전역 메시지 번호. 실제로 읽는 것은 file_id(0), sport(12), session(18), record(20)이다.
    const val MSG_FILE_ID = 0
    const val MSG_SPORT = 12
    const val MSG_SESSION = 18
    const val MSG_LAP = 19
    const val MSG_RECORD = 20
    const val MSG_EVENT = 21
    const val MSG_ACTIVITY = 34

    // file_id 필드. TYPE_ACTIVITY(4)는 활동 파일이라는 뜻이다(테스트용 FitWriter가 이 값으로 파일을 만든다).
    object FileId {
        const val TYPE = 0
        const val MANUFACTURER = 1
        const val PRODUCT = 2
        const val SERIAL_NUMBER = 3
        const val TIME_CREATED = 4

        const val TYPE_ACTIVITY = 4L
    }

    // record 필드(한 시점의 측정값). 단위 변환은 각 줄 끝 주석대로 FitActivity가 적용한다.
    // 파워·심박·케이던스는 배율 없이 W·bpm·rpm 그대로다.
    object Record {
        const val POSITION_LAT = 0
        const val POSITION_LONG = 1
        const val ALTITUDE = 2 // uint16, m = raw / 5 - 500
        const val HEART_RATE = 3
        const val CADENCE = 4
        const val DISTANCE = 5 // uint32, m = raw / 100
        const val SPEED = 6 // uint16, m/s = raw / 1000
        const val POWER = 7
        const val TEMPERATURE = 13
        const val ENHANCED_SPEED = 73 // uint32, m/s = raw / 1000
        const val ENHANCED_ALTITUDE = 78 // uint32, m = raw / 5 - 500
    }

    // session 필드(라이딩 전체 요약). THRESHOLD_POWER는 처음에 112로 잘못 적었다가, python-fitparse 대조
    // 테스트에서 Edge810 파일의 FTP 315가 null로 나와 45로 고쳤다(docs/ai-verification.md FIT 디코더).
    object Session {
        const val START_TIME = 2
        const val SPORT = 5
        const val SUB_SPORT = 6
        const val TOTAL_ELAPSED_TIME = 7 // uint32, s = raw / 1000
        const val TOTAL_TIMER_TIME = 8 // uint32, s = raw / 1000
        const val TOTAL_DISTANCE = 9 // uint32, m = raw / 100
        const val AVG_SPEED = 14 // uint16, m/s = raw / 1000
        const val AVG_POWER = 20
        const val MAX_POWER = 21
        const val TOTAL_ASCENT = 22
        const val NORMALIZED_POWER = 34
        const val TRAINING_STRESS_SCORE = 35 // uint16, raw / 10
        const val INTENSITY_FACTOR = 36 // uint16, raw / 1000
        const val THRESHOLD_POWER = 45 // 처음에는 112로 적었다. python-fitparse의 프로필은 45다
    }

    // sport 메시지 필드와 종목 열거값 일부: 0 generic, 1 running, 2 cycling, 4 fitness_equipment.
    object Sport {
        const val SPORT = 0
        const val SUB_SPORT = 1

        const val GENERIC = 0L
        const val RUNNING = 1L
        const val CYCLING = 2L
        const val FITNESS_EQUIPMENT = 4L
    }

    // event 메시지 필드. 지금은 읽지 않고 번호만 적어 두었다.
    object Event {
        const val EVENT = 0
        const val EVENT_TYPE = 1
        const val DATA = 3
    }

    /**
     * 디코더가 결과에 담는 전역 메시지와 그 필드 번호(timestamp 253은 언제나 담는다). FitActivity가 읽는 것만
     * 적었다: file_id 전부, sport의 종목, session 요약(시작 시각·종목·시간·거리·파워·NP·TSS·IF·FTP·상승),
     * record의 고도·심박·케이던스·거리·속도·파워와 enhanced 속도·고도. 여기에 없는 메시지(lap, event,
     * device_info, hrv 등)와 필드(위치, 온도 등)는 디코더가 구조만 확인하고 값을 담지 않는다.
     * [이유] 2026-10-15 측정에서 모든 메시지·필드를 담으면 10 MB 업로드 하나가 수백 MB의 힙을 붙잡을 수
     * 있었다(docs/experiments.md 5단계 '업로드 자원 증폭'). 새 필드를 읽으려면 여기에 먼저 더해야 한다.
     */
    val KEPT_FIELDS: Map<Int, Set<Int>> = mapOf(
        MSG_FILE_ID to setOf(FileId.TYPE, FileId.MANUFACTURER, FileId.PRODUCT, FileId.SERIAL_NUMBER, FileId.TIME_CREATED),
        MSG_SPORT to setOf(Sport.SPORT, Sport.SUB_SPORT),
        MSG_SESSION to setOf(
            Session.START_TIME, Session.SPORT, Session.SUB_SPORT, Session.TOTAL_ELAPSED_TIME, Session.TOTAL_TIMER_TIME,
            Session.TOTAL_DISTANCE, Session.AVG_POWER, Session.MAX_POWER, Session.TOTAL_ASCENT, Session.NORMALIZED_POWER,
            Session.TRAINING_STRESS_SCORE, Session.INTENSITY_FACTOR, Session.THRESHOLD_POWER,
        ),
        MSG_RECORD to setOf(
            Record.ALTITUDE, Record.HEART_RATE, Record.CADENCE, Record.DISTANCE, Record.SPEED, Record.POWER,
            Record.ENHANCED_SPEED, Record.ENHANCED_ALTITUDE,
        ),
    )

    /**
     * 종목 코드 → 저장·응답에 쓰는 이름. 이 프로젝트가 신경 쓰는 종목 열거값만 이름을 붙이고, 모르는 코드는
     * sport_<번호>로 번호를 그대로 남겨 정보를 잃지 않는다. 3(transition)과 5(swimming)는 상수 없이 숫자로 적었다.
     */
    fun sportName(code: Long?): String? = when (code) {
        null -> null
        Sport.GENERIC -> "generic"
        Sport.RUNNING -> "running"
        Sport.CYCLING -> "cycling"
        3L -> "transition"
        Sport.FITNESS_EQUIPMENT -> "fitness_equipment"
        5L -> "swimming"
        else -> "sport_$code"
    }
}
