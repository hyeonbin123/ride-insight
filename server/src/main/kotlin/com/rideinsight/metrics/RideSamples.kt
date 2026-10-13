package com.rideinsight.metrics

// [파일 역할] 지표 계산의 공통 입력(경과 시간·파워 배열)인 RideSamples와, 기록 간격을 추정하는
// RecordingInterval을 둔 파일이다.
// [설계 이유] OpenData CSV에는 GoldenCheetah가 쓴 기록 간격(recIntSecs)이 없어 연속 샘플 간격의 최빈값으로
// 추정한다(docs/experiments.md 1단계 데이터 규칙). 이 추정이 GC와 다를 수 있다는 점(1.03초처럼 흔들리는
// 타임스탬프)이 4번 질문(기록 조건 강건성)의 한 축이었고, 그런 파일에서 MMP·TSS가 1~3% 어긋나는 것은 README의
// 한계로 남겼다.
// [연결] OpenDataCsv와 MetricsService가 RideSamples를 만들고 PowerMetrics·MeanMaximalPower가 읽는다.
// RecordingInterval은 MetricsService, ActivityService, ParityTool, ImportOpenData가 쓴다.

/**
 * Time series of one ride. [secs] is ascending elapsed time; [power] is watts with missing values
 * stored as 0.0 (GoldenCheetah stores absent series as zero). [powerMissing] counts samples whose
 * power was absent in the source so callers can report the missing ratio.
 */
// 기본형 배열(DoubleArray)이라 수만 개 샘플도 박싱 없이 담는다.
class RideSamples(
    val secs: DoubleArray,
    val power: DoubleArray,
    val powerMissing: Int = 0,
) {
    // 만들 때 두 배열 길이가 같고 secs가 줄어들지 않는지(같은 값은 허용) 확인해, 거꾸로 가는 시각이 지표
    // 계산에 들어가지 않게 한다.
    init {
        require(secs.size == power.size) { "secs and power must have the same length" }
        for (i in 1 until secs.size) {
            require(secs[i] >= secs[i - 1]) { "secs must be non-decreasing at index $i" }
        }
    }

    val size: Int get() = secs.size

    val isEmpty: Boolean get() = secs.isEmpty()
}

// 기록 간격 추정. 규칙: 0보다 큰 간격만 소수 둘째 자리로 반올림해 센 최빈값, 동률이면 짧은 간격.
/** Recording interval of a ride, in seconds. */
object RecordingInterval {
    /**
     * Mode of consecutive time deltas rounded to two decimals. Returns null for fewer than two
     * samples or when every delta is zero. GoldenCheetah stores one interval per file; the
     * OpenData CSV does not carry it, so this estimate stands in for it.
     */
    fun estimate(secs: DoubleArray): Double? = analyse(secs)?.intervalSecs

    // modeShare(최빈 간격이 양수 간격 중 차지하는 비율)가 0.9 미만이면 흔들리는 타임스탬프로 보고한다(1단계
    // 규칙). 활동 테이블에는 interval_share로 저장된다.
    /** The estimated interval and the share of positive deltas equal to it (1.0 = perfectly regular). */
    class Estimate(val intervalSecs: Double, val modeShare: Double)

    // 간격을 0.01초 단위 정수(Math.round(delta × 100))로 바꿔 세면 부동소수 오차 없이 같은 간격끼리 모인다. 0
    // 이하 간격(중복 시각)은 세지 않는다. 샘플이 2개 미만이거나 양수 간격이 없으면 null.
    fun analyse(secs: DoubleArray): Estimate? {
        if (secs.size < 2) return null
        val counts = HashMap<Long, Int>()
        var positive = 0
        for (i in 1 until secs.size) {
            val delta = Math.round((secs[i] - secs[i - 1]) * 100.0)
            if (delta > 0) {
                counts.merge(delta, 1, Int::plus)
                positive++
            }
        }
        // 가장 많이 나온 간격을 고르고, 횟수가 같으면 -key를 비교해 짧은 간격이 이기게 한다.
        val best = counts.entries.maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { -it.key })
            ?: return null
        return Estimate(best.key / 100.0, best.value.toDouble() / positive)
    }
}
