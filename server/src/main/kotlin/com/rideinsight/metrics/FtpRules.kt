package com.rideinsight.metrics

// [파일 역할] 기간 최대 파워(구간 초 → 최대 W)로 FTP를 추정하는 규칙 R1·R2·R3를 순수 함수로 둔 파일이다.
// [설계 이유] 3단계 측정(docs/experiments.md)에서 학습 모델 M2(Ridge)가 R1보다 MAE가 6.3 W 낮았지만, 미리
// 정한 '5 W 이상 개선'을 구간으로 확정하지 못해(H5 판정 불가) 규칙대로 서비스는 R1을 쓴다. R2·R3는 근거로
// 함께 보여 준다. Spring에 의존하지 않는 순수 함수라 단위 테스트(FtpRulesTest)만으로 검증한다.
// [연결] FtpEstimateService가 AthleteService.powerCurve 결과를 넣어 부른다. 3단계 측정은 같은 정의를 ml/의
// ftp.py에 Python으로 구현해 쟀다.

/**
 * Rule-based FTP estimates from a period's best powers (docs/experiments.md, 3단계 R1–R3). Inputs are
 * the per-window maxima in watts keyed by window seconds; null when a window has no value.
 */
object FtpRules {
    // R1: 20분(1200초) 최대 × 0.95. 흔히 쓰는 규칙이다. 봉인 test에서 선수 설정 FTP와 평균 29.2 W 차이였고
    // 평균 17 W 낮게 추정했다(H6 기각). 20분 기록이 없으면 null.
    /** R1: 95 % of the best 20-minute power. */
    fun twentyMinute(best: Map<Int, Double>): Double? = best[1200]?.let { it * 0.95 }

    // R2: 60분(3600초) 최대 그대로. FTP 정의(1시간 유지 가능 파워)를 그대로 잰 값이다. 봉인 test MAE는 47.3
    // W로 후보 중 가장 컸다. 60분 기록이 없으면 null.
    /** R2: the best 60-minute power. */
    fun sixtyMinute(best: Map<Int, Double>): Double? = best[3600]

    // CP 적합에 쓰는 구간: 3·5·8·10·20분(규칙에 미리 적은 값).
    /** Durations used by the critical-power fit: 3, 5, 8, 10 and 20 minutes. */
    val CP_DURATIONS: IntArray = intArrayOf(180, 300, 480, 600, 1200)

    // R3: Critical Power 2변수 모델. 구간 t초 동안의 일(W×초) = 최대 평균 파워 × t가 직선 CP × t + W' 위에
    // 있다고 보고, 값이 있는 구간들에 최소제곱 직선을 맞춰 기울기를 CP, 절편을 W'(J)로 쓴다. 선형 회귀라 닫힌
    // 해가 나온다. 정확한 2변수 곡선(CP 250 W, W' 20 kJ)에서 CP를 1e-6 W, W'를 1e-3 J 안에서 복원하는지
    // FtpRulesTest가 확인한다.
    /**
     * R3: critical power from the two-parameter model work = CP × t + W', least squares over the
     * available durations in [CP_DURATIONS] (at least three needed). Returns CP and W' (joules).
     */
    fun criticalPower(best: Map<Int, Double>): Pair<Double, Double>? {
        val t = ArrayList<Double>()
        val w = ArrayList<Double>()
        // 값이 있는 구간만 점으로 쓴다(각 점: x = 초, y = 파워 × 초 = 일).
        for (d in CP_DURATIONS) {
            val p = best[d] ?: continue
            t.add(d.toDouble())
            w.add(p * d)
        }
        // 점이 3개 미만이면 직선이 불안정하므로 추정하지 않는다(Python 구현과 같은 조건).
        if (t.size < 3) return null
        val n = t.size
        val meanT = t.average()
        val meanW = w.average()
        // 단순 선형 회귀의 닫힌 해: 기울기 = Σ(t − mean(t))(w − mean(w)) / Σ(t − mean(t))², 절편 = mean(w) −
        // 기울기 × mean(t). 모든 t가 같아 분모가 0이면 기울기를 정할 수 없어 null.
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            sxx += (t[i] - meanT) * (t[i] - meanT)
            sxy += (t[i] - meanT) * (w[i] - meanW)
        }
        if (sxx == 0.0) return null
        val cp = sxy / sxx
        val wPrime = meanW - cp * meanT
        return cp to wPrime
    }
}
