package com.rideinsight.metrics

/**
 * Rule-based FTP estimates from a period's best powers (docs/experiments.md, 3단계 R1–R3). Inputs are
 * the per-window maxima in watts keyed by window seconds; null when a window has no value.
 */
object FtpRules {
    /** R1: 95 % of the best 20-minute power. */
    fun twentyMinute(best: Map<Int, Double>): Double? = best[1200]?.let { it * 0.95 }

    /** R2: the best 60-minute power. */
    fun sixtyMinute(best: Map<Int, Double>): Double? = best[3600]

    /** Durations used by the critical-power fit: 3, 5, 8, 10 and 20 minutes. */
    val CP_DURATIONS: IntArray = intArrayOf(180, 300, 480, 600, 1200)

    /**
     * R3: critical power from the two-parameter model work = CP × t + W', least squares over the
     * available durations in [CP_DURATIONS] (at least three needed). Returns CP and W' (joules).
     */
    fun criticalPower(best: Map<Int, Double>): Pair<Double, Double>? {
        val t = ArrayList<Double>()
        val w = ArrayList<Double>()
        for (d in CP_DURATIONS) {
            val p = best[d] ?: continue
            t.add(d.toDouble())
            w.add(p * d)
        }
        if (t.size < 3) return null
        val n = t.size
        val meanT = t.average()
        val meanW = w.average()
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
