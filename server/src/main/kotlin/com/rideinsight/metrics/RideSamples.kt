package com.rideinsight.metrics

/**
 * Time series of one ride. [secs] is ascending elapsed time; [power] is watts with missing values
 * stored as 0.0 (GoldenCheetah stores absent series as zero). [powerMissing] counts samples whose
 * power was absent in the source so callers can report the missing ratio.
 */
class RideSamples(
    val secs: DoubleArray,
    val power: DoubleArray,
    val powerMissing: Int = 0,
) {
    init {
        require(secs.size == power.size) { "secs and power must have the same length" }
        for (i in 1 until secs.size) {
            require(secs[i] >= secs[i - 1]) { "secs must be non-decreasing at index $i" }
        }
    }

    val size: Int get() = secs.size

    val isEmpty: Boolean get() = secs.isEmpty()
}

/** Recording interval of a ride, in seconds. */
object RecordingInterval {
    /**
     * Mode of consecutive time deltas rounded to two decimals. Returns null for fewer than two
     * samples or when every delta is zero. GoldenCheetah stores one interval per file; the
     * OpenData CSV does not carry it, so this estimate stands in for it.
     */
    fun estimate(secs: DoubleArray): Double? = analyse(secs)?.intervalSecs

    /** The estimated interval and the share of positive deltas equal to it (1.0 = perfectly regular). */
    class Estimate(val intervalSecs: Double, val modeShare: Double)

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
        val best = counts.entries.maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { -it.key })
            ?: return null
        return Estimate(best.key / 100.0, best.value.toDouble() / positive)
    }
}
