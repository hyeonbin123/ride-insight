package com.rideinsight.metrics

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenDataCsvTest {
    private fun read(text: String, maxRows: Int = OpenDataCsv.DEFAULT_MAX_ROWS) =
        OpenDataCsv.read(text.reader().buffered(), maxRows)

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

    @Test
    fun `skips rows with unparsable or backwards time`() {
        val r = read("secs,km,power,hr,cad,alt\n0,0,100,,,\nabc,0,100,,,\n5,0,110,,,\n3,0,120,,,\n6,0,130,,,\n")
        assertContentEquals(doubleArrayOf(0.0, 5.0, 6.0), r.samples.secs)
        assertEquals(2, r.badRows)
    }

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

    @Test
    fun `duplicate timestamps are kept and counted`() {
        val r = read("secs,power\n0,200\n0,200\n1,200\n")
        assertContentEquals(doubleArrayOf(0.0, 0.0, 1.0), r.samples.secs)
        assertEquals(1, r.duplicateRows)
        assertEquals(0, r.badRows)
    }

    @Test
    fun `too many rows is rejected`() {
        val text = "secs,power\n" + (0 until 11).joinToString("\n") { "$it,100" } + "\n"
        assertFailsWith<IllegalArgumentException> { read(text, maxRows = 10) }
        assertEquals(10, read("secs,power\n" + (0 until 10).joinToString("\n") { "$it,100" }, maxRows = 10).samples.size)
    }

    @Test
    fun `missing power column yields zeros and a flag`() {
        val r = read("secs,km,hr\n0,0,100\n1,0,101\n")
        assertFalse(r.hasPowerColumn)
        assertContentEquals(doubleArrayOf(0.0, 0.0), r.samples.power)
        assertEquals(2, r.samples.powerMissing)
    }

    @Test
    fun `empty file gives an empty ride`() {
        val r = read("")
        assertTrue(r.samples.isEmpty)
        assertFalse(r.hasPowerColumn)
    }
}
