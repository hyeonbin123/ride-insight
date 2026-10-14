package com.rideinsight.metrics

// [파일 역할] NP·IF·TSS와 기록 간격 추정의 단위 테스트. 손으로 계산한 기대값으로 GoldenCheetah 방식(0으로
// 채운 창에서 시작, 창 크기 = 30 / 간격의 정수 부분, 0 W 포함)을 확인한다.

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerMetricsTest {
    // 부동소수 비교 도우미(기본 허용 오차 1e-9). null이면 실패.
    private fun close(expected: Double, actual: Double?, tolerance: Double = 1e-9) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    // 60초 동안 200 W: 창이 0으로 시작해 처음 29개 창 평균이 200/30부터 차오르므로 NP는 200보다 작다.
    // 기대값도 같은 방식으로 손 계산한다.
    @Test
    fun `constant power gives NP below the constant because the window starts empty`() {
        // 1초 간격 200 W 샘플 60개: 처음 29개 이동 평균은 200/30부터 차오른다.
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

    // 10시간 일정한 250 W면 시작 구간의 영향이 작아 NP가 250에 0.05 W 안으로 수렴한다.
    @Test
    fun `long constant ride converges to the constant`() {
        val np = PowerMetrics.normalizedPower(DoubleArray(36_000) { 250.0 }, 1.0)
        close(250.0, np, 0.05)
    }

    // 5초 기록은 창이 6샘플(30 / 5).
    @Test
    fun `window length follows the recording interval`() {
        // 5초 기록: 창은 6샘플. 100 W 샘플 12개 -> 평균 100/6 .. 100(차오르는 6개 + 꽉 찬 6개).
        val power = DoubleArray(12) { 100.0 }
        var total = 0.0
        for (i in 0 until 12) total += (100.0 * minOf(i + 1, 6) / 6.0).pow(4)
        close((total / 12).pow(0.25), PowerMetrics.normalizedPower(power, 5.0))
    }

    // 샘플 하나: 30칸 창의 평균은 300 / 30 = 10 W라 NP도 10 W. 1.26초 기록이면 창이 23칸.
    @Test
    fun `single sample NP is the sample divided by the window length`() {
        // 30샘플 창 안의 300 W 샘플 하나: 이동 평균 10 W라 NP = 10 W(GC도 같은 값을 낸다).
        close(10.0, PowerMetrics.normalizedPower(doubleArrayOf(300.0), 1.0))
        // 1.26초 기록: 창 = (30 / 1.26).toInt() = 23샘플.
        close(300.0 / 23.0, PowerMetrics.normalizedPower(doubleArrayOf(300.0), 1.26))
    }

    // 창이 1샘플 이하(간격 30초·20초)이거나 샘플이 없으면 null(GC는 이때 0을 보고).
    @Test
    fun `window of one sample or less has no NP`() {
        assertNull(PowerMetrics.normalizedPower(DoubleArray(100) { 100.0 }, 30.0))
        assertNull(PowerMetrics.normalizedPower(DoubleArray(100) { 100.0 }, 20.0))
        assertNull(PowerMetrics.normalizedPower(DoubleArray(0), 1.0))
    }

    // 0 W 샘플을 빼지 않는지: 300/0이 번갈아 나오면 30초 평균이 150이라 NP는 150 W 일정 라이딩과 거의 같다.
    // 0을 건너뛰는 구현이면 약 300이 나와 실패한다.
    @Test
    fun `zero samples are included not skipped`() {
        val withZeros = DoubleArray(3600) { if (it % 2 == 0) 300.0 else 0.0 }
        val constant = DoubleArray(3600) { 150.0 }
        val npZeros = PowerMetrics.normalizedPower(withZeros, 1.0)!!
        val npConst = PowerMetrics.normalizedPower(constant, 1.0)!!
        // 300/0이 번갈아 나오면 꽉 찬 30초 창마다 평균이 150이라 NP가 일정한 라이딩과 가깝다(처음 29개의
        // 덜 찬 창만 조금 다르다). 0을 빼고 계산했다면 약 300이 나왔을 것이다.
        close(npConst, npZeros, 0.01)
        assertTrue(npZeros < 160.0)
    }

    // IF·TSS 공식: 200 / 250 = 0.8, FTP로 1시간 = 100 TSS, 5초 × 720샘플(1시간)에 IF 0.8이면 64 TSS. FTP가
    // 없거나 0이면 null.
    @Test
    fun `IF and TSS follow the Coggan formulas`() {
        close(0.8, PowerMetrics.intensityFactor(200.0, 250.0))
        assertNull(PowerMetrics.intensityFactor(200.0, null))
        assertNull(PowerMetrics.intensityFactor(200.0, 0.0))
        // IF 1.0으로 1시간이면 100 TSS.
        close(100.0, PowerMetrics.trainingStressScore(250.0, 3600, 1.0, 250.0))
        // 5초 기록 720샘플 = 1시간, IF 0.8 -> 64 TSS.
        close(64.0, PowerMetrics.trainingStressScore(200.0, 720, 5.0, 250.0))
        assertNull(PowerMetrics.trainingStressScore(200.0, 720, 5.0, null))
    }

    // 기록 간격 = 간격의 최빈값(1초 간격 사이에 한 번 있는 2초 틈은 무시), 5초, 1.26초. 샘플 1개이거나 모든
    // 간격이 0이면 null.
    @Test
    fun `recording interval is the mode of deltas`() {
        assertEquals(1.0, RecordingInterval.estimate(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 5.0)))
        assertEquals(5.0, RecordingInterval.estimate(doubleArrayOf(0.0, 5.0, 10.0, 15.0)))
        assertEquals(1.26, RecordingInterval.estimate(doubleArrayOf(0.0, 1.26, 2.52, 3.78, 5.04)))
        assertNull(RecordingInterval.estimate(doubleArrayOf(7.0)))
        assertNull(RecordingInterval.estimate(doubleArrayOf(1.0, 1.0, 1.0)))
    }

    // modeShare: 규칙적인 파일은 1.0. OpenData의 흔들리는 파일처럼 간격이 1.00·1.03·1.00·1.02초면 최빈값
    // 1.00, 비율 0.5.
    @Test
    fun `mode share reports how regular the timestamps are`() {
        val regular = RecordingInterval.analyse(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0))!!
        assertEquals(1.0, regular.modeShare)
        // OpenData의 2007_11_19처럼 흔들리는 파일: 간격 1.00, 1.03, 1.00, 1.02 -> 최빈값 1.00, 비율 0.5.
        val jitter = RecordingInterval.analyse(doubleArrayOf(0.0, 1.0, 2.03, 3.03, 4.05))!!
        assertEquals(1.0, jitter.intervalSecs)
        assertEquals(0.5, jitter.modeShare)
    }
}
