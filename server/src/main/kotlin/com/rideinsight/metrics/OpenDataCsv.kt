package com.rideinsight.metrics

import java.io.BufferedReader

/**
 * Reader for GoldenCheetah OpenData ride CSV files (`secs,km,power,hr,cad,alt`).
 *
 * - blank or non-finite power cells become 0.0 and are counted in [RideSamples.powerMissing]
 * - rows whose `secs` is missing, non-finite or earlier than the previous row are skipped and
 *   counted in [Result.badRows]
 * - rows whose `secs` equals the previous row are kept (GoldenCheetah keeps them too) and counted
 *   in [Result.duplicateRows] so callers can report them
 * - more than [maxRows] rows is rejected to bound memory on hostile or corrupt input
 */
object OpenDataCsv {
    const val DEFAULT_MAX_ROWS = 1_000_000

    class Result(
        val samples: RideSamples,
        val badRows: Int,
        val duplicateRows: Int,
        val hasPowerColumn: Boolean,
    )

    fun read(reader: BufferedReader, maxRows: Int = DEFAULT_MAX_ROWS): Result {
        val header = reader.readLine()
            ?: return Result(RideSamples(DoubleArray(0), DoubleArray(0)), 0, 0, false)
        val columns = header.split(',').map { it.trim() }
        val secsIndex = columns.indexOf("secs")
        val powerIndex = columns.indexOf("power")
        require(secsIndex >= 0) { "CSV has no 'secs' column: $header" }

        val secs = ArrayList<Double>()
        val power = ArrayList<Double>()
        var missing = 0
        var bad = 0
        var duplicates = 0
        var lastSecs = Double.NEGATIVE_INFINITY
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cells = line.split(',')
            val s = cells.getOrNull(secsIndex)?.trim()?.toDoubleOrNull()
            if (s == null || !s.isFinite() || s < lastSecs) {
                bad++
                continue
            }
            if (s == lastSecs && secs.isNotEmpty()) duplicates++
            lastSecs = s
            val p = if (powerIndex >= 0) cells.getOrNull(powerIndex)?.trim()?.toDoubleOrNull() else null
            val finitePower = p?.takeIf { it.isFinite() }
            if (finitePower == null) missing++
            secs.add(s)
            power.add(finitePower ?: 0.0)
            require(secs.size <= maxRows) { "CSV has more than $maxRows rows" }
        }
        return Result(
            RideSamples(secs.toDoubleArray(), power.toDoubleArray(), missing),
            bad,
            duplicates,
            powerIndex >= 0,
        )
    }
}
