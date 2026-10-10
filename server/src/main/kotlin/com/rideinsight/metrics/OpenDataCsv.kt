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
 * - the other series (heart rate, cadence, distance, altitude) are kept as nullable lists in
 *   [Result.extras] for storage; they do not influence the power metrics
 */
object OpenDataCsv {
    const val DEFAULT_MAX_ROWS = 1_000_000

    class Extras(
        val heartRate: List<Int?>?,
        val cadence: List<Int?>?,
        val distanceM: List<Double?>?,
        val altitudeM: List<Double?>?,
    )

    class Result(
        val samples: RideSamples,
        val badRows: Int,
        val duplicateRows: Int,
        val hasPowerColumn: Boolean,
        val extras: Extras,
    )

    fun read(reader: BufferedReader, maxRows: Int = DEFAULT_MAX_ROWS): Result {
        val header = reader.readLine()
            ?: return Result(RideSamples(DoubleArray(0), DoubleArray(0)), 0, 0, false, Extras(null, null, null, null))
        val columns = header.split(',').map { it.trim() }
        val secsIndex = columns.indexOf("secs")
        val powerIndex = columns.indexOf("power")
        val hrIndex = columns.indexOf("hr")
        val cadIndex = columns.indexOf("cad")
        val kmIndex = columns.indexOf("km")
        val altIndex = columns.indexOf("alt")
        require(secsIndex >= 0) { "CSV has no 'secs' column: $header" }

        val secs = ArrayList<Double>()
        val power = ArrayList<Double>()
        val hr = ArrayList<Int?>()
        val cad = ArrayList<Int?>()
        val dist = ArrayList<Double?>()
        val alt = ArrayList<Double?>()
        var missing = 0
        var bad = 0
        var duplicates = 0
        var lastSecs = Double.NEGATIVE_INFINITY
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cells = line.split(',')
            fun num(index: Int): Double? =
                if (index >= 0) cells.getOrNull(index)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } else null
            val s = num(secsIndex)
            if (s == null || s < lastSecs) {
                bad++
                continue
            }
            if (s == lastSecs && secs.isNotEmpty()) duplicates++
            lastSecs = s
            val p = num(powerIndex)
            if (p == null) missing++
            secs.add(s)
            power.add(p ?: 0.0)
            hr.add(num(hrIndex)?.let { Math.round(it).toInt() })
            cad.add(num(cadIndex)?.let { Math.round(it).toInt() })
            dist.add(num(kmIndex)?.let { it * 1000.0 })
            alt.add(num(altIndex))
            require(secs.size <= maxRows) { "CSV has more than $maxRows rows" }
        }
        fun <T> some(list: List<T?>): List<T?>? = list.takeIf { l -> l.any { it != null } }
        return Result(
            RideSamples(secs.toDoubleArray(), power.toDoubleArray(), missing),
            bad,
            duplicates,
            powerIndex >= 0,
            Extras(some(hr), some(cad), some(dist), some(alt)),
        )
    }
}
