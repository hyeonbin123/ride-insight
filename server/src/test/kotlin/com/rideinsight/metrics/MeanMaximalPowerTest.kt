package com.rideinsight.metrics

// [파일 역할] MMP(N초 최대 평균 파워) 단위 테스트. 손으로 계산할 수 있는 작은 라이딩으로 GoldenCheetah
// findPeaks 규칙(창 지속 시간, 앞에서 밀어내기, 라이딩 길이, 3000 W 가드, 기록 간격 가중)을 하나씩 확인한다.
// [왜 중요한가] 1단계 H2(GC와 1 W 안 일치)의 바탕이 이 규칙들이다. 틈 테스트의 기대값은 처음에 AI가 틀리게
// 적었다가 GC 소스로 확인해 고쳤다(창의 지속 시간은 [N, N + 간격) 안이어야 함, docs/ai-verification.md
// 1단계).

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeanMaximalPowerTest {
    // 간격 interval로 균일하게 기록된 라이딩을 만드는 도우미(secs = 0, interval, 2 × interval, ...).
    private fun ride(vararg power: Double, interval: Double = 1.0): RideSamples =
        RideSamples(DoubleArray(power.size) { it * interval }, power)

    // 부동소수 비교 도우미(기본 허용 오차 1e-9). null이면 실패.
    private fun close(expected: Double, actual: Double?, tolerance: Double = 1e-9) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    // 연속 샘플의 최대 평균: 1초 400, 2초 (300 + 400) / 2, 3초 (200 + 300 + 400) / 3, 6초는 전체 평균.
    @Test
    fun `best window is the maximal average of consecutive samples`() {
        val r = ride(100.0, 200.0, 300.0, 400.0, 100.0, 100.0)
        close(400.0, MeanMaximalPower.best(r, 1.0, 1.0))
        close(350.0, MeanMaximalPower.best(r, 1.0, 2.0))
        close(300.0, MeanMaximalPower.best(r, 1.0, 3.0))
        close(200.0, MeanMaximalPower.best(r, 1.0, 6.0))
    }

    // 라이딩보다 긴 구간은 null. 길이는 마지막 secs + 간격이라 샘플 3개(0·1·2초)면 3초 구간까지 된다. 빈
    // 라이딩도 null.
    @Test
    fun `ride shorter than the window has no value`() {
        val r = ride(100.0, 200.0, 300.0)
        assertNull(MeanMaximalPower.best(r, 1.0, 4.0))
        // Total length is last.secs + recIntSecs = 3 s, so a 3 s window still fits.
        close(200.0, MeanMaximalPower.best(r, 1.0, 3.0))
        assertNull(MeanMaximalPower.best(RideSamples(DoubleArray(0), DoubleArray(0)), 1.0, 1.0))
    }

    // 5초 기록: 샘플 하나가 5초를 대표하므로 10초 구간 = 샘플 2개, 25초는 라이딩(20초)보다 길어 null.
    @Test
    fun `five second recording weights each sample by the interval`() {
        // 4 samples at 5 s = 20 s of riding. 10 s window = 2 samples.
        val r = ride(100.0, 300.0, 200.0, 100.0, interval = 5.0)
        close(250.0, MeanMaximalPower.best(r, 5.0, 10.0))
        close(200.0, MeanMaximalPower.best(r, 5.0, 15.0))
        close(175.0, MeanMaximalPower.best(r, 5.0, 20.0))
        assertNull(MeanMaximalPower.best(r, 5.0, 25.0))
    }

    // 기록이 멈춘 틈: 지속 시간은 벽시계 시간, 합은 샘플 값이라 틈이 창에 들어가면 평균이 낮아진다. 이
    // 기대값은 처음에 '4개 샘플 모두가 한 창에 든다'고 잘못 적었던 부분이다.
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

    // 3000 W 가드: 평균이 3000 W 이상(정확히 3000 포함)이면 0, 2999는 그대로.
    @Test
    fun `peak power guard zeroes implausible averages`() {
        val r = ride(3500.0, 3500.0)
        assertEquals(0.0, MeanMaximalPower.peakPower(r, 1.0, 1.0))
        close(2999.0, MeanMaximalPower.peakPower(ride(2999.0, 2999.0), 1.0, 1.0))
        // GoldenCheetah keeps only averages strictly below 3000 W.
        assertEquals(0.0, MeanMaximalPower.peakPower(ride(3000.0, 3000.0), 1.0, 1.0))
    }

    // Codex 1차 검토가 제안한 경계 입력: 첫 샘플이 최고, 샘플 하나, 중복 시각(GC처럼 합이 됨), 1.26초 간격.
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

    // 구간 키 이름이 GC 메타데이터 이름과 같은지, 구간이 16개인지. 이름이 다르면 대조·저장 키가 어긋난다.
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
