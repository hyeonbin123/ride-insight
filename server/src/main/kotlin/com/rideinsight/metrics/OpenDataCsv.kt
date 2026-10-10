package com.rideinsight.metrics

import java.io.BufferedReader

/**
 * Reader for GoldenCheetah OpenData ride CSV files (`secs,km,power,hr,cad,alt`). Blank power
 * cells become 0.0 and are counted in [RideSamples.powerMissing]. Rows whose `secs` cannot be
 * parsed are skipped and counted in [Result.badRows].
 */
object OpenDataCsv {
    class Result(val samples: RideSamples, val badRows: Int, val hasPowerColumn: Boolean)

    fun read(reader: BufferedReader): Result {
        val header = reader.readLine() ?: return Result(RideSamples(DoubleArray(0), DoubleArray(0)), 0, false)
        val columns = header.split(',').map { it.trim() }
        val secsIndex = columns.indexOf("secs")
        val powerIndex = columns.indexOf("power")
        require(secsIndex >= 0) { "CSV has no 'secs' column: $header" }

        val secs = ArrayList<Double>()
        val power = ArrayList<Double>()
        var missing = 0
        var bad = 0
        var lastSecs = Double.NEGATIVE_INFINITY
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cells = line.split(',')
            val s = cells.getOrNull(secsIndex)?.trim()?.toDoubleOrNull()
            if (s == null || s < lastSecs) {
                bad++
                continue
            }
            lastSecs = s
            val p = if (powerIndex >= 0) cells.getOrNull(powerIndex)?.trim()?.toDoubleOrNull() else null
            if (p == null) missing++
            secs.add(s)
            power.add(p ?: 0.0)
        }
        return Result(RideSamples(secs.toDoubleArray(), power.toDoubleArray(), missing), bad, powerIndex >= 0)
    }
}
