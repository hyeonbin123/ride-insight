package com.rideinsight.metrics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeanMaximalPowerTest {
    private fun ride(vararg power: Double, interval: Double = 1.0): RideSamples =
        RideSamples(DoubleArray(power.size) { it * interval }, power)

    private fun close(expected: Double, actual: Double?, tolerance: Double = 1e-9) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun `best window is the maximal average of consecutive samples`() {
        val r = ride(100.0, 200.0, 300.0, 400.0, 100.0, 100.0)
        close(400.0, MeanMaximalPower.best(r, 1.0, 1.0))
        close(350.0, MeanMaximalPower.best(r, 1.0, 2.0))
        close(300.0, MeanMaximalPower.best(r, 1.0, 3.0))
        close(200.0, MeanMaximalPower.best(r, 1.0, 6.0))
    }

    @Test
    fun `ride shorter than the window has no value`() {
        val r = ride(100.0, 200.0, 300.0)
        assertNull(MeanMaximalPower.best(r, 1.0, 4.0))
        // Total length is last.secs + recIntSecs = 3 s, so a 3 s window still fits.
        close(200.0, MeanMaximalPower.best(r, 1.0, 3.0))
        assertNull(MeanMaximalPower.best(RideSamples(DoubleArray(0), DoubleArray(0)), 1.0, 1.0))
    }

    @Test
    fun `five second recording weights each sample by the interval`() {
        // 4 samples at 5 s = 20 s of riding. 10 s window = 2 samples.
        val r = ride(100.0, 300.0, 200.0, 100.0, interval = 5.0)
        close(250.0, MeanMaximalPower.best(r, 5.0, 10.0))
        close(200.0, MeanMaximalPower.best(r, 5.0, 15.0))
        close(175.0, MeanMaximalPower.best(r, 5.0, 20.0))
        assertNull(MeanMaximalPower.best(r, 5.0, 25.0))
    }

    @Test
    fun `gaps make the window cover fewer samples and lower the average`() {
        // A 10 s pause between the 2nd and 3rd sample: duration counts wall time, sum counts samples.
        val r = RideSamples(doubleArrayOf(0.0, 1.0, 11.0, 12.0), doubleArrayOf(300.0, 300.0, 300.0, 300.0))
        // 2 s window: consecutive pairs give 300; the pair (1, 11) has duration 11 and is evicted.
        close(300.0, MeanMaximalPower.best(r, 1.0, 2.0))
        // 12 s window: windows must have duration in [12, 13), so {0, 1, 11} (duration 12) qualifies
        // and {0, 1, 11, 12} (duration 13) is evicted down to {1, 11, 12}. Average = 3 × 300 / 12.
        close(75.0, MeanMaximalPower.best(r, 1.0, 12.0))
        // 13 s window: nothing shorter than 14 s spans 13 s here except the whole ride (duration 13).
        close(4 * 300.0 / 13.0, MeanMaximalPower.best(r, 1.0, 13.0))
    }

    @Test
    fun `peak power guard zeroes implausible averages`() {
        val r = ride(3500.0, 3500.0)
        assertEquals(0.0, MeanMaximalPower.peakPower(r, 1.0, 1.0))
        close(2999.0, MeanMaximalPower.peakPower(ride(2999.0, 2999.0), 1.0, 1.0))
        // GoldenCheetah keeps only averages strictly below 3000 W.
        assertEquals(0.0, MeanMaximalPower.peakPower(ride(3000.0, 3000.0), 1.0, 1.0))
    }

    @Test
    fun `edge inputs from the Codex review`() {
        // Best window at the very first sample.
        close(400.0, MeanMaximalPower.best(ride(400.0, 100.0), 1.0, 1.0))
        // A single sample covers exactly one recording interval.
        close(200.0, MeanMaximalPower.best(ride(200.0), 1.0, 1.0))
        assertNull(MeanMaximalPower.best(ride(200.0), 1.0, 2.0))
        // Duplicate timestamps: two samples at 0 s span one interval, so the 1 s "average" is their
        // sum. This matches GoldenCheetah's window rule; the CSV reader counts such rows so they can
        // be reported rather than silently trusted.
        val dup = RideSamples(doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(200.0, 200.0, 200.0))
        close(400.0, MeanMaximalPower.best(dup, 1.0, 1.0))
        // Fractional recording interval: 4 samples at 1.26 s span 5.04 s; a 5 s window needs all four.
        val frac = ride(100.0, 200.0, 300.0, 400.0, interval = 1.26)
        close(1000.0 * 1.26 / 5.04, MeanMaximalPower.best(frac, 1.26, 5.0))
    }

    @Test
    fun `metric keys match GoldenCheetah symbols`() {
        assertEquals("1s_critical_power", MeanMaximalPower.metricKey(1))
        assertEquals("30s_critical_power", MeanMaximalPower.metricKey(30))
        assertEquals("1m_critical_power", MeanMaximalPower.metricKey(60))
        assertEquals("20m_critical_power", MeanMaximalPower.metricKey(1200))
        assertEquals("90m_critical_power", MeanMaximalPower.metricKey(5400))
        assertEquals(16, MeanMaximalPower.STANDARD_WINDOWS_SECS.size)
    }
}
