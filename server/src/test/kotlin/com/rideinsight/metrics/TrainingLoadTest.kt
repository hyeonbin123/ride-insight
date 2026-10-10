package com.rideinsight.metrics

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrainingLoadTest {
    private fun close(expected: Double, actual: Double, tolerance: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")

    @Test
    fun `three day hand example`() {
        val d1 = LocalDate.of(2024, 3, 1)
        val tss = mapOf(d1 to 100.0, d1.plusDays(2) to 50.0)
        val s = TrainingLoad.series(d1, d1.plusDays(2), tss)
        val kC = 1 - exp(-1.0 / 42)
        val kA = 1 - exp(-1.0 / 7)
        // Day 1: from zero, TSB 0.
        close(100 * kC, s[0].ctl)
        close(100 * kA, s[0].atl)
        close(0.0, s[0].tsb)
        // Day 2: no activity, loads decay, TSB is day-1 CTL minus day-1 ATL (negative after a hard day).
        close(s[0].ctl * (1 - kC), s[1].ctl)
        close(s[0].atl * (1 - kA), s[1].atl)
        close(s[0].ctl - s[0].atl, s[1].tsb)
        assertTrue(s[1].tsb < 0)
        // Day 3: 50 TSS.
        close(s[1].ctl + (50 - s[1].ctl) * kC, s[2].ctl)
        close(s[1].atl + (50 - s[1].atl) * kA, s[2].atl)
        assertEquals(3, s.size)
        assertEquals(50.0, s[2].tss)
    }

    @Test
    fun `constant daily load converges to that load`() {
        val from = LocalDate.of(2024, 1, 1)
        val to = from.plusDays(400)
        val tss = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.associateWith { 60.0 }
        val last = TrainingLoad.series(from, to, tss).last()
        close(60.0, last.ctl, 0.01)
        close(60.0, last.atl, 1e-6)
        close(0.0, last.tsb, 0.01)
    }

    @Test
    fun `initial loads carry over and invalid ranges are rejected`() {
        val d = LocalDate.of(2024, 5, 5)
        val s = TrainingLoad.series(d, d, emptyMap(), initialCtl = 40.0, initialAtl = 20.0)
        close(20.0, s[0].tsb)
        close(40.0 * exp(-1.0 / 42), s[0].ctl)
        assertFailsWith<IllegalArgumentException> { TrainingLoad.series(d, d.minusDays(1), emptyMap()) }
    }
}
