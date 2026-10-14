package com.rideinsight.metrics

// [파일 역할] FTP 규칙(R1·R2·R3) 단위 테스트. 서비스가 실제로 돌려주는 추정값(R1)과 근거 값(R2, R3)의 정의를
// 손 계산과 정확한 곡선으로 확인한다.

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FtpRulesTest {
    // R1 = 20분 × 0.95(300 → 285), R2 = 60분 그대로, 20분 값이 없으면 R1은 null.
    @Test
    fun `rules follow their definitions`() {
        val best = mapOf(1200 to 300.0, 3600 to 280.0)
        assertEquals(285.0, FtpRules.twentyMinute(best))
        assertEquals(280.0, FtpRules.sixtyMinute(best))
        assertNull(FtpRules.twentyMinute(emptyMap()))
    }

    // P(t) = CP + W'/t인 정확한 2변수 곡선에서는 일 = CP × t + W'가 완전한 직선이므로 최소제곱이 CP 250 W, W'
    // 20 kJ를 그대로 돌려줘야 한다. 회귀 공식(기울기·절편)의 실수를 잡는다.
    @Test
    fun `critical power recovers an exact two-parameter curve`() {
        // P(t) = CP + W'/t, CP 250 W, W' 20 kJ.
        val best = FtpRules.CP_DURATIONS.associateWith { 250.0 + 20_000.0 / it }
        val (cp, wPrime) = assertNotNull(FtpRules.criticalPower(best))
        assertTrue(abs(cp - 250.0) < 1e-6, "cp $cp")
        assertTrue(abs(wPrime - 20_000.0) < 1e-3, "W' $wPrime")
    }

    // 최소 조건: 구간이 2개면 null, 3개면 값이 나온다.
    @Test
    fun `critical power needs at least three durations`() {
        assertNull(FtpRules.criticalPower(mapOf(180 to 400.0, 300 to 360.0)))
        assertNotNull(FtpRules.criticalPower(mapOf(180 to 400.0, 300 to 360.0, 1200 to 300.0)))
    }
}
