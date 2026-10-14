package com.rideinsight.api

// [파일 역할] REST API의 요청·응답 본문 형태(DTO)를 모은 파일이다.
// [설계 이유] 저장소 계층의 행 클래스(Athlete, ActivityRow, MetricsRow, SampleSeries)를 그대로 내보내지 않고
// 응답 전용 클래스로 옮겨, API 계약(필드 이름·구성)을 DB 구조와 떼어 놓는다. 각 응답 클래스의 from()이 그
// 변환을 맡는다.
// [연결] 컨트롤러(ActivityController, AthleteController, ApiExceptionHandler)가 만든다. 화면의
// web/src/api/types.ts가 이 파일을 거울처럼 따라 TypeScript 타입을 정의한다. Jackson이 JSON으로 바꿀 때 null
// 필드는 응답에서 빠진다(application.properties의 default-property-inclusion=non_null).

import com.rideinsight.service.StoredActivity
import com.rideinsight.storage.Athlete
import com.rideinsight.storage.MetricsRow
import com.rideinsight.storage.SampleSeries
import com.rideinsight.storage.Threshold
// Bean Validation 애너테이션: 컨트롤러에서 @Valid가 붙은 요청 본문을 메서드 실행 전에 검사한다. 실패하면
// ApiExceptionHandler가 400 validation으로 바꾼다.
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// POST /api/athletes 본문. 제약은 docs/api.md 완료 기준(이름 1~100자, FTP 50~1000 W)과 DB의 check
// 제약(V1__init.sql: 성별 M/F, 출생 연도 1900~2100)에 맞췄다. gender·birthYear·weightKg·ftp는 선택 항목이라
// 기본값 null이다.
// 체중(weightKg)은 DB check(20 < weight_kg < 300)와 같은 열린 구간으로 검사한다. 처음에는 검증이 없어 범위
// 밖 체중이 DB check 위반 예외로 500이 됐다(2026-10-13 수정). 컬럼이 real(4바이트 부동소수)이라 요청 값도
// Float으로 받는다: Double로 검사하면 20.0000001처럼 범위 안이지만 real로 저장할 때 20.0으로 반올림돼 check에
// 걸리는 값이 검사를 통과하기 때문이다. Float으로 받으면 검사하는 값과 저장되는 값이 같다(Float 범위를 넘는
// 수는 무한대가 되어 역시 거절된다).
data class CreateAthleteRequest(
    @NotBlank @Size(max = 100) val name: String,
    @Pattern(regexp = "M|F") val gender: String? = null,
    @Min(1900) @Max(2100) val birthYear: Int? = null,
    @DecimalMin(value = "20", inclusive = false) @DecimalMax(value = "300", inclusive = false) val weightKg: Float? = null,
    @Min(50) @Max(1000) val ftp: Int? = null,
)

// PUT /api/athletes/{id}/threshold 본문. effectiveFrom은 ISO 날짜(yyyy-MM-dd)이고 FTP 범위는 선수 생성과
// 같다. effectiveFrom은 null이 될 수 없는 생성자 인자라, 빠지면 JSON을 객체로 만들지 못해 400 bad_request가
// 된다.
data class ThresholdRequest(
    val effectiveFrom: LocalDate,
    @Min(50) @Max(1000) val ftpWatts: Int,
)

// FTP 이력 한 줄: 적용 시작일, 와트, 출처(manual 직접 입력, import OpenData 가져오기, estimate 추정).
data class ThresholdResponse(val effectiveFrom: LocalDate, val ftpWatts: Int, val source: String) {
    companion object {
        fun from(t: Threshold) = ThresholdResponse(t.effectiveFrom, t.ftpWatts, t.source)
    }
}

// 선수 정보 + 오늘 기준으로 유효한 FTP(currentThreshold, 등록된 FTP가 없으면 생략).
data class AthleteResponse(
    val id: UUID,
    val name: String,
    val gender: String?,
    val birthYear: Int?,
    val weightKg: Double?,
    val createdAt: OffsetDateTime,
    val currentThreshold: ThresholdResponse?,
) {
    companion object {
        fun from(a: Athlete, t: Threshold?) =
            AthleteResponse(a.id, a.name, a.gender, a.birthYear, a.weightKg, a.createdAt, t?.let(ThresholdResponse::from))
    }
}

// FIT file_id 메시지의 제조사·제품 번호(FIT 프로필의 숫자 코드 그대로). OpenData에서 가져온 활동은 둘 다
// null이다.
data class DeviceResponse(val manufacturer: Int?, val product: Int?)

// 활동 하나의 지표: 계산에 쓴 FTP, NP, IF, TSS, 평균·최대 파워(W), 일(kJ), 16구간 MMP(키는 GoldenCheetah
// 메타데이터 이름, 라이딩이 구간보다 짧으면 값 null). FTP가 없으면 IF·TSS는 null이다.
data class MetricsResponse(
    val ftpUsed: Int?,
    val normalizedPower: Double?,
    val intensityFactor: Double?,
    val tss: Double?,
    val averagePower: Double?,
    val maxPower: Int?,
    val workKj: Double?,
    val mmp: Map<String, Double?>,
) {
    companion object {
        fun from(m: MetricsRow) =
            MetricsResponse(m.ftpUsed, m.normalizedPower, m.intensityFactor, m.tss, m.averagePower, m.maxPower, m.workKj, m.mmp)
    }
}

// 활동 요약 + 지표.
// - recIntSecs: 샘플 간격의 최빈값으로 추정한 기록 간격(초). intervalShare: 그 간격이 전체 간격에서 차지하는
//   비율(1이면 완전히 규칙적, 낮으면 타임스탬프가 흔들리는 파일).
// - crcValid: FIT CRC 일치 여부. CRC가 틀린 파일도 거절하지 않고 저장하되 false로 표시한다(5단계 H8 기대
//   결과). OpenData 활동은 null.
// - hasPower·powerMissing: 파워 시리즈가 있는지와 파워가 빠진 샘플 수. 파워가 없는 활동은 metrics가 없다.
data class ActivityResponse(
    val id: UUID,
    val athleteId: UUID,
    val startedAt: OffsetDateTime,
    val source: String,
    val sourceName: String?,
    val sport: String?,
    val recIntSecs: Double?,
    val intervalShare: Double?,
    val sampleCount: Int,
    val durationSecs: Double?,
    val device: DeviceResponse,
    val crcValid: Boolean?,
    val hasPower: Boolean,
    val powerMissing: Int,
    val metrics: MetricsResponse?,
) {
    companion object {
        fun from(s: StoredActivity): ActivityResponse {
            val a = s.activity
            return ActivityResponse(
                a.id, a.athleteId, a.startedAt, a.source, a.sourceName, a.sport, a.recIntSecs, a.intervalShare,
                a.sampleCount, a.durationSecs, DeviceResponse(a.manufacturer, a.product), a.crcValid, a.hasPower,
                a.powerMissing, s.metrics?.let(MetricsResponse::from),
            )
        }
    }
}

// 활동 목록 한 페이지와 다음 페이지 커서(nextBefore, 마지막 페이지면 생략).
data class ActivityListResponse(val items: List<ActivityResponse>, val nextBefore: OffsetDateTime?)

// 시계열 응답. every는 실제로 쓴 구간 폭(초)이다. 없는 시리즈(예: 파워미터가 없는 라이딩의 power)는 null이라
// 응답에서 빠지고, 시리즈 안에서 빠진 샘플은 원소 null로 남는다.
data class SamplesResponse(
    val every: Int,
    val secs: List<Double>,
    val power: List<Int?>?,
    val heartRate: List<Int?>?,
    val cadence: List<Int?>?,
    val distanceM: List<Double?>?,
    val altitudeM: List<Double?>?,
) {
    companion object {
        // 경과 시간 t를 every로 나눠 내림한 값(구간 번호)이 바뀌는 첫 샘플의 인덱스를 모은 뒤, 모든
        // 시리즈에서 같은 인덱스를 고른다. 샘플 개수가 아니라 시간 기준이라, 기록 간격이 1초가 아니거나
        // 중간에 멈춘 구간이 있어도 대략 every초에 한 점이 된다(Codex 2차 검토 지적 6). 시리즈들이 같은
        // 인덱스를 공유하므로 그래프에서 시각과 값이 어긋나지 않는다.
        /** 경과 시간 [every]초마다 한 점, 곧 각 구간의 첫 샘플을 고른다. */
        fun from(s: SampleSeries, every: Int): SamplesResponse {
            val keep = ArrayList<Int>()
            var lastBucket = Long.MIN_VALUE
            for ((i, t) in s.secs.withIndex()) {
                val bucket = Math.floor(t / every).toLong()
                if (bucket != lastBucket) {
                    keep.add(i)
                    lastBucket = bucket
                }
            }
            // 고른 인덱스로 시리즈를 추려 내는 지역 도우미. 시리즈가 null(없음)이면 null을 그대로 둔다.
            // secs는 항상 있으므로 아래에서 !!로 꺼낸다.
            fun <T> pick(list: List<T>?): List<T>? = list?.let { l -> keep.map { l[it] } }
            return SamplesResponse(
                every, pick(s.secs)!!, pick(s.power), pick(s.heartRate), pick(s.cadence), pick(s.distanceM), pick(s.altitudeM),
            )
        }
    }
}

// 파워 커브의 한 점: 지속 시간(초)과 그 시간 동안의 최대 평균 파워(W).
data class CurvePoint(val secs: Int, val watts: Double)

// points는 화면에 그리는 곡선, standard는 16개 표준 구간 값(키는 GoldenCheetah 이름). 활동 파워 커브와 선수
// 기간 파워 커브가 같은 형태를 쓴다.
data class PowerCurveResponse(val points: List<CurvePoint>, val standard: Map<String, Double?>)

// PMC 하루 값: 그날 TSS 합, CTL(42일 지수 가중), ATL(7일), TSB(어제 CTL − 어제 ATL).
data class TrainingLoadDay(val date: LocalDate, val tss: Double, val ctl: Double, val atl: Double, val tsb: Double)

data class TrainingLoadResponse(val days: List<TrainingLoadDay>)

// 모든 오류 응답의 본문: 프로그램이 분기에 쓰는 코드(error, 예: invalid_fit)와 사람이 읽는 설명(message).
// docs/api.md의 오류 형식이다.
data class ErrorResponse(val error: String, val message: String)

// FTP 추정 응답.
// - method: 실제로 쓴 추정기(R1). ftpWatts: 추정값(20분 기록이 없으면 null).
// - rules: R1·R2·R3 값, criticalPowerWPrimeKj: CP 모델의 W'(kJ).
// - windowsUsed: 값이 있는 표준 MMP 구간 수, ridesInWindow: 기간 안 파워 라이딩 수, best20m·best60m:
//   20분·60분 최대 파워.
// - note: 규칙과 측정한 오차(dev·봉인 test MAE, 편향)를 적은 설명 문장(docs/experiments.md 규칙 변경 7).
data class FtpEstimateResponse(
    val method: String,
    val ftpWatts: Double?,
    val rules: Map<String, Double?>,
    val criticalPowerWPrimeKj: Double?,
    val windowsUsed: Int,
    val ridesInWindow: Int,
    val best20m: Double?,
    val best60m: Double?,
    val weeks: Int,
    val note: String,
)
