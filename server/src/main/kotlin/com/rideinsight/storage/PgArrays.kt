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
 * PostgreSQL array literals for binding (`'{1,2,NULL}'::smallint[]`) and reading back. Literal
 * binding avoids driver-specific boxed-array support and keeps NULL elements.
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

// nullable 숫자 읽기 확장 함수. PgJDBC는 float4 컬럼을 getObject(컬럼, java.lang.Double)로 읽지 못해, 처음
// 구현에서 선수 조회가 전부 500이었다(통합 테스트가 잡음, docs/ai-verification.md 2단계). 그래서 기본형으로
// 읽은 뒤 wasNull()로 SQL NULL인지 확인한다.
// Nullable primitive readers. PgJDBC refuses getObject(column, java.lang.Double::class.java) on a
// float4 column ("float4 cannot be converted to Double"), so read primitives and check wasNull.
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
