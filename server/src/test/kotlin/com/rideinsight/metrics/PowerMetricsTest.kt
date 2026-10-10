package com.rideinsight.metrics

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerMetricsTest {
    private fun close(expected: Double, actual: Double?, tolerance: Double = 1e-9) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun `constant power gives NP below the constant because the window starts empty`() {
        // 60 samples of 200 W at 1 s: the first 29 rolling averages ramp up from 200/30.
        val power = DoubleArray(60) { 200.0 }
        val np = PowerMetrics.normalizedPower(power, 1.0)
        var total = 0.0
        for (i in 0 until 60) {
            val filled = minOf(i + 1, 30)
            total += (200.0 * filled / 30.0).pow(4)
        }
        close((total / 60).pow(0.25), np)
        assertTrue(np!! < 200.0)
    }

    @Test
    fun `long constant ride converges to the constant`() {
        val np = PowerMetrics.normalizedPower(DoubleArray(36_000) { 250.0 }, 1.0)
        close(250.0, np, 0.05)
    }

    @Test
    fun `window length follows the recording interval`() {
        // 5 s recording: 6-sample window. 12 samples of 100 W -> averages 100/6 .. 100 (6 ramp + 6 full).
        val power = DoubleArray(12) { 100.0 }
        var total = 0.0
        for (i in 0 until 12) total += (100.0 * minOf(i + 1, 6) / 6.0).pow(4)
        close((total / 12).pow(0.25), PowerMetrics.normalizedPower(power, 5.0))
    }

    @Test
    fun `single sample NP is the sample divided by the window length`() {
        // One 300 W sample in a 30-sample window: rolling average 10 W, so NP = 10 W (GC gives the same).
        close(10.0, PowerMetrics.normalizedPower(doubleArrayOf(300.0), 1.0))
        // 1.26 s recording: window = (30 / 1.26).toInt() = 23 samples.
        close(300.0 / 23.0, PowerMetrics.normalizedPower(doubleArrayOf(300.0), 1.26))
    }

    @Test
    fun `window of one sample or less has no NP`() {
        assertNull(PowerMetrics.normalizedPower(DoubleArray(100) { 100.0 }, 30.0))
        assertNull(PowerMetrics.normalizedPower(DoubleArray(100) { 100.0 }, 20.0))
        assertNull(PowerMetrics.normalizedPower(DoubleArray(0), 1.0))
    }

    @Test
    fun `zero samples are included not skipped`() {
        val withZeros = DoubleArray(3600) { if (it % 2 == 0) 300.0 else 0.0 }
        val constant = DoubleArray(3600) { 150.0 }
        val npZeros = PowerMetrics.normalizedPower(withZeros, 1.0)!!
        val npConst = PowerMetrics.normalizedPower(constant, 1.0)!!
        // Alternating 300/0 averages to 150 inside every full 30 s window, so NP is close to the
        // constant ride (the first 29 partial windows differ slightly). Skipping zeros would give ~300.
        close(npConst, npZeros, 0.01)
        assertTrue(npZeros < 160.0)
    }

    @Test
    fun `IF and TSS follow the Coggan formulas`() {
        close(0.8, PowerMetrics.intensityFactor(200.0, 250.0))
        assertNull(PowerMetrics.intensityFactor(200.0, null))
        assertNull(PowerMetrics.intensityFactor(200.0, 0.0))
        // One hour at IF 1.0 is 100 TSS.
        close(100.0, PowerMetrics.trainingStressScore(250.0, 3600, 1.0, 250.0))
        // 5 s recording, 720 samples = 1 h at IF 0.8 -> 64 TSS.
        close(64.0, PowerMetrics.trainingStressScore(200.0, 720, 5.0, 250.0))
        assertNull(PowerMetrics.trainingStressScore(200.0, 720, 5.0, null))
    }

    @Test
    fun `recording interval is the mode of deltas`() {
        assertEquals(1.0, RecordingInterval.estimate(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 5.0)))
        assertEquals(5.0, RecordingInterval.estimate(doubleArrayOf(0.0, 5.0, 10.0, 15.0)))
        assertEquals(1.26, RecordingInterval.estimate(doubleArrayOf(0.0, 1.26, 2.52, 3.78, 5.04)))
        assertNull(RecordingInterval.estimate(doubleArrayOf(7.0)))
        assertNull(RecordingInterval.estimate(doubleArrayOf(1.0, 1.0, 1.0)))
    }

    @Test
    fun `mode share reports how regular the timestamps are`() {
        val regular = RecordingInterval.analyse(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0))!!
        assertEquals(1.0, regular.modeShare)
        // Jittered file like 2007_11_19 in OpenData: deltas 1.00, 1.03, 1.00, 1.02 -> mode 1.00 with share 0.5.
        val jitter = RecordingInterval.analyse(doubleArrayOf(0.0, 1.0, 2.03, 3.03, 4.05))!!
        assertEquals(1.0, jitter.intervalSecs)
        assertEquals(0.5, jitter.modeShare)
    }
}
