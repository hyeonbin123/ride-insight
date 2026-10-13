package com.rideinsight.metrics

// [파일 역할] OpenData CSV 읽기 단위 테스트: 결측 파워, 깨진·역행 행, NaN·Infinity, 중복 시각, 행 수 상한,
// power 열 없음, 빈 파일. 1단계 규칙의 CSV 처리 조항(docs/experiments.md 1단계 데이터)을 하나씩 확인한다.

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenDataCsvTest {
    // 문자열을 BufferedReader로 감싸 읽는 도우미.
    private fun read(text: String, maxRows: Int = OpenDataCsv.DEFAULT_MAX_ROWS) =
        OpenDataCsv.read(text.reader().buffered(), maxRows)

    // 빈 파워 칸은 0 W로 넣고 결측 1개로 센다.
    @Test
    fun `parses power and counts blanks as missing zeros`() {
        val r = read("secs,km,power,hr,cad,alt\n0,0,,100,,67\n1,0.01,150,101,80,67\n2,0.02,160,102,81,67\n")
        assertTrue(r.hasPowerColumn)
        assertContentEquals(doubleArrayOf(0.0, 1.0, 2.0), r.samples.secs)
        assertContentEquals(doubleArrayOf(0.0, 150.0, 160.0), r.samples.power)
        assertEquals(1, r.samples.powerMissing)
        assertEquals(0, r.badRows)
        assertEquals(0, r.duplicateRows)
    }

    // 숫자가 아닌 secs(abc)와 직전보다 작은 secs(5 다음 3)는 버리고 깨진 행 2개로 센다.
    @Test
    fun `skips rows with unparsable or backwards time`() {
        val r = read("secs,km,power,hr,cad,alt\n0,0,100,,,\nabc,0,100,,,\n5,0,110,,,\n3,0,120,,,\n6,0,130,,,\n")
        assertContentEquals(doubleArrayOf(0.0, 5.0, 6.0), r.samples.secs)
        assertEquals(2, r.badRows)
    }

    // Codex 1차 검토 지적 2의 회귀 테스트: 'NaN'·'Infinity' 파워는 결측(0 W), secs가 NaN·Infinity인 행은 깨진
    // 행. 결과 파워에 유한하지 않은 값이 하나도 없어야 한다.
    @Test
    fun `non-finite values are missing power or bad rows, never propagated`() {
        // Codex review 2026-10-10: NaN parsed by toDoubleOrNull would have reached NP.
        val r = read("secs,power\n0,NaN\n1,200\nNaN,200\nInfinity,200\n2,Infinity\n3,-Infinity\n")
        assertContentEquals(doubleArrayOf(0.0, 1.0, 2.0, 3.0), r.samples.secs)
        assertContentEquals(doubleArrayOf(0.0, 200.0, 0.0, 0.0), r.samples.power)
        assertEquals(3, r.samples.powerMissing)
        assertEquals(2, r.badRows)
        assertTrue(r.samples.power.all { it.isFinite() })
    }

    // 같은 secs가 반복된 행은 버리지 않고(GC와 같게) 중복으로 센다.
    @Test
    fun `duplicate timestamps are kept and counted`() {
        val r = read("secs,power\n0,200\n0,200\n1,200\n")
        assertContentEquals(doubleArrayOf(0.0, 0.0, 1.0), r.samples.secs)
        assertEquals(1, r.duplicateRows)
        assertEquals(0, r.badRows)
    }

    // 행 수 상한: 11행은 거절(IllegalArgumentException), 정확히 10행은 통과(경계값).
    @Test
    fun `too many rows is rejected`() {
        val text = "secs,power\n" + (0 until 11).joinToString("\n") { "$it,100" } + "\n"
        assertFailsWith<IllegalArgumentException> { read(text, maxRows = 10) }
        assertEquals(10, read("secs,power\n" + (0 until 10).joinToString("\n") { "$it,100" }, maxRows = 10).samples.size)
    }

    // power 열이 없으면 파워는 모두 0, 결측 수 = 행 수, hasPowerColumn = false.
    @Test
    fun `missing power column yields zeros and a flag`() {
        val r = read("secs,km,hr\n0,0,100\n1,0,101\n")
        assertFalse(r.hasPowerColumn)
        assertContentEquals(doubleArrayOf(0.0, 0.0), r.samples.power)
        assertEquals(2, r.samples.powerMissing)
    }

    // 빈 파일(헤더도 없음)은 예외 없이 빈 라이딩.
    @Test
    fun `empty file gives an empty ride`() {
        val r = read("")
        assertTrue(r.samples.isEmpty)
        assertFalse(r.hasPowerColumn)
    }
}
