package com.rideinsight.metrics

// [파일 역할] Coggan 파워 지표 NP(Normalized Power: 강도 변화를 반영한 환산 평균 파워), IF(Intensity Factor:
// NP / FTP), TSS(Training Stress Score: 강도와 시간을 합친 훈련 부하)를 계산하는 순수 함수다.
// [설계 이유] 1단계 목적이 GoldenCheetah가 계산해 둔 값과의 대조였기 때문에, 처음 30초를 0으로 채운 창에서
// 시작하는 GC 방식까지 그대로 따랐다(docs/plan.md 지표 정의). 봉인 세트의 1초 라이딩에서 NP 99.61%가 1 W 안,
// TSS 99.97%가 0.5 안에서 일치했다(docs/experiments.md 1단계). Spring에 의존하지 않아 단위
// 테스트(PowerMetricsTest)만으로 검증한다.
// [연결] MetricsService(업로드·가져온 활동의 지표), ParityTool(GC 대조), ApiIntegrationTest(업로드 결과를 이
// 함수로 다시 계산해 비교)가 쓴다.

import kotlin.math.pow

/**
 * Coggan 파워 지표. 결과를 OpenData 메타데이터와 대조할 수 있도록 GoldenCheetah(src/Metrics/Coggan.cpp)와
 * 같게 구현했다. docs/experiments.md 1단계 참고.
 */
object PowerMetrics {
    /** Normalized Power가 쓰는 이동 평균 창 길이(초). Coggan 정의대로 30초다. */
    const val NP_WINDOW_SECS = 30.0

    /**
     * Normalized Power. 30초 이동 평균을 4제곱해 모든 샘플에서 평균한 뒤 4제곱근을 취한다. 이동 평균 창은
     * `(30 / recIntSecs)`의 정수 부분만큼의 샘플을 담고(1초면 30, 5초면 6, 1.26초면 23), 0으로 채워진 채
     * 시작하며, GoldenCheetah와 똑같이 시작 부분을 잘라 내지 않는다. 0 W 샘플도 빼지 않는다. 창에 샘플이
     * 하나 이하만 들어가는 경우(GoldenCheetah는 이때 0을 낸다)나 샘플이 없으면 null을 돌려준다.
     */
    fun normalizedPower(power: DoubleArray, recIntSecs: Double): Double? {
        if (power.isEmpty() || recIntSecs <= 0.0) return null
        val window = (NP_WINDOW_SECS / recIntSecs).toInt()
        if (window <= 1) return null
        // 원형 버퍼: 최근 window개 샘플. 0으로 채워 시작하므로 처음 window − 1개의 평균은 실제보다 낮다(GC와
        // 같게 일부러 자르지 않는다).
        val buffer = DoubleArray(window)
        var index = 0
        var sum = 0.0
        var total = 0.0
        for (watts in power) {
            // 합을 매번 다시 더하지 않고, 새 값을 더하고 창에서 빠지는 값을 빼서 샘플마다 O(1)로 갱신한다.
            sum += watts - buffer[index]
            buffer[index] = watts
            index = (index + 1) % window
            // 창 평균의 4제곱을 누적한다. 시작 구간에서도 나누는 수는 항상 창 크기다.
            total += (sum / window).pow(4)
        }
        return (total / power.size).pow(0.25)
    }

    /** Intensity Factor = NP / 임계 파워(FTP). FTP가 없거나 0 이하면 null이고, 그러면 TSS도 계산하지 않는다. */
    fun intensityFactor(normalizedPower: Double, thresholdWatts: Double?): Double? {
        if (thresholdWatts == null || thresholdWatts <= 0.0) return null
        return normalizedPower / thresholdWatts
    }

    /**
     * Training Stress Score = (NP × duration × IF) / (FTP × 3600) × 100. duration은 `sampleCount × recIntSecs`
     * 초다. GoldenCheetah는 이 시간을 NP 메트릭의 `count`(이미 초 단위)로 저장해 두고 같은 방식으로 NP·IF와
     * 곱한다. FTP와 같은 파워로 1시간을 타면 100이다. 시간이 '샘플 수 × 기록 간격'이라 기록이 멈춘 시간은
     * 들어가지 않는다. FTP가 TSS에 제곱으로 들어가므로(IF에 한 번, 분모에 한 번) FTP를 바꾸면 TSS는 1/FTP²
     * 비율로 바뀐다(ApiIntegrationTest가 확인).
     */
    fun trainingStressScore(
        normalizedPower: Double,
        sampleCount: Int,
        recIntSecs: Double,
        thresholdWatts: Double?,
    ): Double? {
        val intensity = intensityFactor(normalizedPower, thresholdWatts) ?: return null
        val durationSecs = sampleCount * recIntSecs
        // IF가 null이 아니면 FTP도 양수로 확인된 것이므로 !!가 안전하다.
        return normalizedPower * durationSecs * intensity / (thresholdWatts!! * 3600.0) * 100.0
    }
}
