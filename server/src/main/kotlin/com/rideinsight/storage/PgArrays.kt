package com.rideinsight.storage

import java.sql.ResultSet

/**
 * PostgreSQL array literals for binding (`'{1,2,NULL}'::smallint[]`) and reading back. Literal
 * binding avoids driver-specific boxed-array support and keeps NULL elements.
 */
object PgArrays {
    fun literal(values: Iterable<Number?>): String =
        values.joinToString(",", "{", "}") { v ->
            when (v) {
                null -> "NULL"
                is Double -> if (v.isFinite()) v.toString() else "NULL"
                is Float -> if (v.isFinite()) v.toString() else "NULL"
                else -> v.toString()
            }
        }

    fun readDoubles(rs: ResultSet, column: String): List<Double?>? {
        val array = rs.getArray(column) ?: return null
        return (array.array as Array<*>).map { (it as Number?)?.toDouble() }
    }

    fun readInts(rs: ResultSet, column: String): List<Int?>? {
        val array = rs.getArray(column) ?: return null
        return (array.array as Array<*>).map { (it as Number?)?.toInt() }
    }
}

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
