package com.rideinsight.metrics

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
    /** Durations (seconds) of GoldenCheetah's PeakPower metrics, in metadata order. */
    val STANDARD_WINDOWS_SECS: IntArray = intArrayOf(
        1, 5, 10, 15, 20, 30, 60, 120, 180, 300, 480, 600, 1200, 1800, 3600, 5400,
    )

    /** Metadata keys for [STANDARD_WINDOWS_SECS], e.g. `20m_critical_power`. */
    fun metricKey(windowSecs: Int): String =
        if (windowSecs < 60) "${windowSecs}s_critical_power" else "${windowSecs / 60}m_critical_power"

    /** Best average over [windowSecs], or null when the ride is shorter than the window or empty. */
    fun best(samples: RideSamples, recIntSecs: Double, windowSecs: Double): Double? {
        val secs = samples.secs
        val power = samples.power
        if (secs.isEmpty() || recIntSecs <= 0.0) return null
        if (windowSecs > secs.last() + recIntSecs) return null

        var start = 0
        var total = 0.0
        var best: Double? = null
        for (end in secs.indices) {
            while (start < end && secs[end] - secs[start] + recIntSecs >= windowSecs + recIntSecs) {
                total -= power[start]
                start++
            }
            total += power[end]
            val duration = secs[end] - secs[start] + recIntSecs
            if (duration >= windowSecs) {
                val avg = total * recIntSecs / duration
                if (best == null || avg > best) best = avg
            }
        }
        return best
    }

    /** [best] with GoldenCheetah's PeakPower guard: averages of 3000 W or more become 0. */
    fun peakPower(samples: RideSamples, recIntSecs: Double, windowSecs: Double): Double? {
        val value = best(samples, recIntSecs, windowSecs) ?: return null
        return if (value < 3000.0) value else 0.0
    }
}
