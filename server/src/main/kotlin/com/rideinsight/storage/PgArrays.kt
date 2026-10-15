package com.rideinsight.storage

// [파일 역할] PostgreSQL 배열 컬럼 도우미. 값 목록을 배열 리터럴 문자열('{1,2,NULL}')로 만들고, 읽을 때는
// java.sql.Array를 Kotlin 리스트로 바꾼다. 파일 끝에는 ResultSet에서 nullable 숫자를 읽는 확장 함수가 있다.
// [설계 이유] 리터럴 문자열로 바인딩하고 SQL에서 ::smallint[]처럼 형 변환하면, 드라이버마다 다른 박싱 배열
// 지원에 기대지 않고 NULL 원소도 지킬 수 있다. 리터럴에는 숫자와 NULL만 들어가고 그 문자열도 ? 자리표시자로
// 바인딩되므로 SQL 주입 경로가 없다(Codex 2차 검토가 정상으로 확인).
// [연결] ActivityRepository가 샘플 저장·조회에 쓰고, 확장 함수는 ActivityRepository·AthleteRepository의 행
// 매핑이 쓴다.

import java.sql.ResultSet

/**
 * 바인딩용 PostgreSQL 배열 리터럴(`'{1,2,NULL}'::smallint[]`)을 만들고 배열 컬럼을 다시 읽는다. 리터럴로
 * 바인딩하면 드라이버마다 다른 박싱 배열 지원에 기대지 않고 NULL 원소도 지킬 수 있다.
 */
object PgArrays {
    // 숫자 목록 → '{v1,v2,...}'. null과 NaN·Infinity는 NULL 원소로 바꿔 DB에 숫자가 아닌 값이 들어가지 않게
    // 한다. 정수는 toString 그대로 쓴다.
    fun literal(values: Iterable<Number?>): String =
        values.joinToString(",", "{", "}") { v ->
            when (v) {
                null -> "NULL"
                is Double -> if (v.isFinite()) v.toString() else "NULL"
                is Float -> if (v.isFinite()) v.toString() else "NULL"
                else -> v.toString()
            }
        }

    /** 정수 배열에서 '값 없음'을 나타내는 표시(parseInts의 NULL 원소). */
    const val ABSENT_INT = Int.MIN_VALUE

    /**
     * PostgreSQL 숫자 배열의 텍스트 표현('{1,2.5,NULL}')을 DoubleArray로 푼다. NULL 원소는 NaN, 입력이 null(SQL
     * NULL 컬럼)이면 null. 숫자 배열의 텍스트에는 따옴표·공백이 없다(PostgreSQL 배열 출력 형식).
     */
    fun parseDoubles(text: String?): DoubleArray? {
        if (text == null) return null
        val out = DoubleArray(countElements(text))
        var k = 0
        forEachElement(text) { from, to ->
            out[k++] = if (isNull(text, from, to)) Double.NaN else text.substring(from, to).toDouble()
        }
        return out
    }

    /** 같은 방식으로 정수 배열('{1,-2,NULL}')을 IntArray로 푼다. NULL 원소는 [ABSENT_INT]. */
    fun parseInts(text: String?): IntArray? {
        if (text == null) return null
        val out = IntArray(countElements(text))
        var k = 0
        forEachElement(text) { from, to ->
            out[k++] = if (isNull(text, from, to)) ABSENT_INT else parseInt(text, from, to)
        }
        return out
    }

    /** 원시 배열 위의 읽기 전용 목록 보기(복사하지 않음). NaN은 null로 보인다. */
    fun view(values: DoubleArray): List<Double?> = object : AbstractList<Double?>() {
        override val size: Int get() = values.size
        override fun get(index: Int): Double? = values[index].takeIf { !it.isNaN() }
    }

    /** 원시 배열 위의 읽기 전용 목록 보기(복사하지 않음). [ABSENT_INT]는 null로 보인다. */
    fun view(values: IntArray): List<Int?> = object : AbstractList<Int?>() {
        override val size: Int get() = values.size
        override fun get(index: Int): Int? = values[index].takeIf { it != ABSENT_INT }
    }

    // '{}'면 0개, 아니면 쉼표 수 + 1개.
    private fun countElements(text: String): Int {
        if (text.length <= 2) return 0
        var n = 1
        for (i in 1 until text.length - 1) if (text[i] == ',') n++
        return n
    }

    // 중괄호 안의 원소 구간 [from, to)를 차례로 넘긴다.
    private inline fun forEachElement(text: String, action: (Int, Int) -> Unit) {
        if (text.length <= 2) return
        var start = 1
        for (i in 1 until text.length) {
            if (text[i] == ',' || i == text.length - 1) {
                action(start, i)
                start = i + 1
            }
        }
    }

    private fun isNull(text: String, from: Int, to: Int) = to - from == 4 && text.regionMatches(from, "NULL", 0, 4)

    // 부호 있는 십진 정수를 문자열 조각에서 바로 읽는다(원소마다 String을 만들지 않는다).
    private fun parseInt(text: String, from: Int, to: Int): Int {
        var i = from
        val negative = text[i] == '-'
        if (negative) i++
        var v = 0
        while (i < to) {
            v = v * 10 + (text[i] - '0')
            i++
        }
        return if (negative) -v else v
    }

    // 배열 컬럼 → List<Double?>. 컬럼이 SQL NULL(시리즈 없음)이면 null. 원소는 컬럼 형에 따라 다른 숫자
    // 형으로 오므로 Number로 받아 바꾼다.
    fun readDoubles(rs: ResultSet, column: String): List<Double?>? {
        val array = rs.getArray(column) ?: return null
        return (array.array as Array<*>).map { (it as Number?)?.toDouble() }
    }

    // 같은 방식으로 List<Int?>.
    fun readInts(rs: ResultSet, column: String): List<Int?>? {
        val array = rs.getArray(column) ?: return null
        return (array.array as Array<*>).map { (it as Number?)?.toInt() }
    }
}

// nullable 숫자 읽기 확장 함수. PgJDBC는 float4 컬럼을 getObject(컬럼, java.lang.Double::class.java)로 읽지
// 못하고 "float4 cannot be converted to Double" 오류를 내서, 처음 구현에서 선수 조회가 전부 500이었다(통합
// 테스트가 잡음, docs/ai-verification.md 2단계). 그래서 기본형으로 읽은 뒤 wasNull()로 SQL NULL인지 확인한다.
fun ResultSet.doubleOrNull(column: String): Double? {
    val v = getDouble(column)
    return if (wasNull()) null else v
}

fun ResultSet.intOrNull(column: String): Int? {
    val v = getInt(column)
    return if (wasNull()) null else v
}

fun ResultSet.boolOrNull(column: String): Boolean? {
    val v = getBoolean(column)
    return if (wasNull()) null else v
}
