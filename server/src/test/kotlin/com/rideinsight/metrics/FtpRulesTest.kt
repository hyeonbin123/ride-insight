package com.rideinsight.metrics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FtpRulesTest {
    @Test
    fun `rules follow their definitions`() {
        val best = mapOf(1200 to 300.0, 3600 to 280.0)
        assertEquals(285.0, FtpRules.twentyMinute(best))
        assertEquals(280.0, FtpRules.sixtyMinute(best))
        assertNull(FtpRules.twentyMinute(emptyMap()))
    }

    @Test
    fun `critical power recovers an exact two-parameter curve`() {
        // P(t) = CP + W'/t with CP 250 W, W' 20 kJ.
        val best = FtpRules.CP_DURATIONS.associateWith { 250.0 + 20_000.0 / it }
        val (cp, wPrime) = assertNotNull(FtpRules.criticalPower(best))
        assertTrue(abs(cp - 250.0) < 1e-6, "cp $cp")
        assertTrue(abs(wPrime - 20_000.0) < 1e-3, "W' $wPrime")
    }

    @Test
    fun `critical power needs at least three durations`() {
        assertNull(FtpRules.criticalPower(mapOf(180 to 400.0, 300 to 360.0)))
        assertNotNull(FtpRules.criticalPower(mapOf(180 to 400.0, 300 to 360.0, 1200 to 300.0)))
    }
}
