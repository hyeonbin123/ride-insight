package com.rideinsight.metrics

// [파일 역할] N초 최대 평균 파워(MMP, mean-maximal power: 라이딩 안에서 N초 동안 낸 평균 파워의 최댓값, 파워
// 커브의 한 점)를 계산하는 순수 함수다.
// [설계 이유] 1단계 목표가 GoldenCheetah 값과의 일치(H2: 1초 라이딩의 (라이딩, 16구간) 쌍 중 99% 이상이 1 W
// 안)였기 때문에, GC의 findPeaks 규칙(창 지속 시간 정의, 앞에서부터 밀어내기, 3000 W 가드)을 아래 설명 그대로
// 옮겼다. 봉인 세트에서 99.65%가 1 W 안에서 일치했다(docs/experiments.md 1단계).
// [연결] MetricsService(활동마다 16구간 저장), ActivityService.powerCurve(활동 전 구간 곡선), ParityTool(GC
// 대조)이 쓰고, AthleteService·AthleteController는 기간 커브의 키 이름(metricKey)과 구간 목록을 쓴다.

/**
 * Best average power over a window, following GoldenCheetah's `AddIntervalDialog::findPeaks`
 * (src/Gui/AddIntervalDialog.cpp) so results can be compared with the OpenData
 * `Ns_critical_power` metrics.
 *
 * GoldenCheetah semantics reproduced here:
 * - interval duration of a window = last.secs - first.secs + recIntSecs
 * - points are evicted from the front while `duration(first, incoming) >= window + recIntSecs`
 * - a window is a candidate once its duration >= window; its average = sum × recIntSecs / duration
 * - a ride whose total length (last.secs + recIntSecs) is shorter than the window has no value
 * - `PeakPower` reports 0 instead of a best average of 3000 W or more
 */
object MeanMaximalPower {
    // GC 메타데이터에 있는 16개 구간: 1·5·10·15·20·30초, 1·2·3·5·8·10·20·30·60·90분. 활동 지표, 기간 파워
    // 커브, FTP 특징이 모두 이 구간을 쓴다.
    /** Durations (seconds) of GoldenCheetah's PeakPower metrics, in metadata order. */
    val STANDARD_WINDOWS_SECS: IntArray = intArrayOf(
        1, 5, 10, 15, 20, 30, 60, 120, 180, 300, 480, 600, 1200, 1800, 3600, 5400,
    )

    // 구간 초 → GC 메타데이터 키. 60초 미만은 초(s), 이상은 분(m) 단위 이름이다. DB의 mmp jsonb와 API 응답도
    // 이 키를 쓴다.
    /** Metadata keys for [STANDARD_WINDOWS_SECS], e.g. `20m_critical_power`. */
    fun metricKey(windowSecs: Int): String =
        if (windowSecs < 60) "${windowSecs}s_critical_power" else "${windowSecs / 60}m_critical_power"

    // 입력: 시계열, 기록 간격(초), 구간 길이(초). 출력: 최대 평균 W 또는 null. 두 포인터(start, end)로 창을
    // 한 번만 훑으므로 샘플 수에 비례하는 시간(O(n))이 든다.
    /** Best average over [windowSecs], or null when the ride is shorter than the window or empty. */
    fun best(samples: RideSamples, recIntSecs: Double, windowSecs: Double): Double? {
        val secs = samples.secs
        val power = samples.power
        // 빈 라이딩이나 간격 추정 실패(0 이하)는 값 없음. 라이딩 전체 길이(마지막 secs + 기록 간격)가
        // 구간보다 짧아도 값 없음(GC 규칙).
        if (secs.isEmpty() || recIntSecs <= 0.0) return null
        if (windowSecs > secs.last() + recIntSecs) return null

        var start = 0
        var total = 0.0
        var best: Double? = null
        for (end in secs.indices) {
            // 창을 앞에서 줄이는 규칙(GC와 같음): 새 샘플까지 넣었을 때의 지속 시간이 '구간 + 기록 간격'
            // 이상이면 맨 앞 샘플을 뺀다. 그 결과 후보 창의 지속 시간은 [구간, 구간 + 간격) 안에 들고, 중간에
            // 멈춘 틈이 있으면 창에 든 샘플 수가 줄어 평균이 낮아진다(MeanMaximalPowerTest의 gaps 테스트).
            while (start < end && secs[end] - secs[start] + recIntSecs >= windowSecs + recIntSecs) {
                total -= power[start]
                start++
            }
            total += power[end]
            // 지속 시간 = 마지막 − 첫 secs + 기록 간격. 지속 시간이 구간 이상인 창만 후보다. 평균 = 샘플 합 ×
            // 간격 / 지속 시간이라, 각 샘플이 기록 간격만큼의 시간을 대표한다(5초 기록이면 한 샘플이 5초).
            val duration = secs[end] - secs[start] + recIntSecs
            if (duration >= windowSecs) {
                val avg = total * recIntSecs / duration
                if (best == null || avg > best) best = avg
            }
        }
        return best
    }

    // GC의 PeakPower 메트릭과 같은 값: 평균이 3000 W 이상이면(정확히 3000 W도) 0으로 바꾼다.
    // 저장·대조(MetricsService, ParityTool)에는 이 함수를 쓰고, 활동 전 구간
    // 곡선(ActivityService.powerCurve)은 가드 없는 best를 쓴다.
    /** [best] with GoldenCheetah's PeakPower guard: averages of 3000 W or more become 0. */
    fun peakPower(samples: RideSamples, recIntSecs: Double, windowSecs: Double): Double? {
        val value = best(samples, recIntSecs, windowSecs) ?: return null
        return if (value < 3000.0) value else 0.0
    }
}
