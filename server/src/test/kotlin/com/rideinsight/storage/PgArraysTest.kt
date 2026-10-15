package com.rideinsight.storage

// [파일 역할] PostgreSQL 배열 텍스트('{1,2.5,NULL}')를 원시 배열로 푸는 PgArrays.parseDoubles·parseInts와 그 목록
// 보기를 확인하는 단위 테스트. 샘플 조회(ActivityRepository.samples)가 2026-10-15부터 드라이버의 박싱 배열 대신
// 이 함수를 쓰므로, 빈 배열·NULL 원소·음수·지수 표기와 literal → parse 왕복을 고정한다.

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PgArraysTest {
    @Test
    fun `empty and null inputs`() {
        assertNull(PgArrays.parseDoubles(null))
        assertNull(PgArrays.parseInts(null))
        assertEquals(0, PgArrays.parseDoubles("{}")!!.size)
        assertEquals(0, PgArrays.parseInts("{}")!!.size)
    }

    @Test
    fun `doubles with nulls, negatives and exponents`() {
        val d = PgArrays.parseDoubles("{1,2.5,NULL,-3,1e+06,4.5e-05}")!!
        assertEquals(6, d.size)
        assertEquals(listOf(1.0, 2.5, null, -3.0, 1e6, 4.5e-5), PgArrays.view(d))
        assertTrue(d[2].isNaN())
    }

    @Test
    fun `ints with nulls and negatives`() {
        val i = PgArrays.parseInts("{0,NULL,-32000,32000,7}")!!
        assertContentEquals(intArrayOf(0, PgArrays.ABSENT_INT, -32000, 32000, 7), i)
        assertEquals(listOf(0, null, -32000, 32000, 7), PgArrays.view(i))
    }

    // 저장할 때 쓰는 literal과 읽을 때 쓰는 parse가 서로 맞는지(정수 시리즈와 실수 시리즈).
    @Test
    fun `literal and parse round trip`() {
        val ints = listOf(250, null, 0, 1999)
        assertEquals(ints, PgArrays.view(PgArrays.parseInts(PgArrays.literal(ints))!!))
        val doubles = listOf(0.0, 1.5, null, 123456.25)
        assertEquals(doubles, PgArrays.view(PgArrays.parseDoubles(PgArrays.literal(doubles))!!))
    }
}
