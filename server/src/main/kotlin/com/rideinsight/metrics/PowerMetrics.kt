package com.rideinsight.metrics

import kotlin.math.pow

/**
 * Coggan power metrics, implemented to match GoldenCheetah (src/Metrics/Coggan.cpp) so that the
 * results can be checked against the OpenData metadata. See docs/experiments.md, stage 1.
 */
object PowerMetrics {
    /** Rolling-average window length used by Normalized Power, in seconds. */
    const val NP_WINDOW_SECS = 30.0

    /**
     * Normalized Power. The rolling window holds `(30 / recIntSecs)` samples (truncated), starts
     * filled with zeros and is never trimmed at the start, exactly like GoldenCheetah. Returns null
     * when the window would hold at most one sample (GoldenCheetah reports 0 in that case) or when
     * there are no samples.
     */
    fun normalizedPower(power: DoubleArray, recIntSecs: Double): Double? {
        if (power.isEmpty() || recIntSecs <= 0.0) return null
        val window = (NP_WINDOW_SECS / recIntSecs).toInt()
        if (window <= 1) return null
        val buffer = DoubleArray(window)
        var index = 0
        var sum = 0.0
        var total = 0.0
        for (watts in power) {
            sum += watts - buffer[index]
            buffer[index] = watts
            index = (index + 1) % window
            total += (sum / window).pow(4)
        }
        return (total / power.size).pow(0.25)
    }

    /** Intensity Factor = NP / threshold power. Null when the threshold is missing or not positive. */
    fun intensityFactor(normalizedPower: Double, thresholdWatts: Double?): Double? {
        if (thresholdWatts == null || thresholdWatts <= 0.0) return null
        return normalizedPower / thresholdWatts
    }

    /**
     * Training Stress Score = (NP × duration × IF) / (threshold × 3600) × 100, where duration is
     * `sampleCount × recIntSecs` (GoldenCheetah's `np->count()` times the recording interval).
     */
    fun trainingStressScore(
        normalizedPower: Double,
        sampleCount: Int,
        recIntSecs: Double,
        thresholdWatts: Double?,
    ): Double? {
        val intensity = intensityFactor(normalizedPower, thresholdWatts) ?: return null
        val durationSecs = sampleCount * recIntSecs
        return normalizedPower * durationSecs * intensity / (thresholdWatts!! * 3600.0) * 100.0
    }
}
