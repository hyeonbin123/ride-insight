package com.rideinsight.tools

import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.OpenDataCsv
import com.rideinsight.metrics.PowerMetrics
import com.rideinsight.metrics.RecordingInterval
import java.io.File
import java.util.zip.ZipFile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Offline tool for docs/experiments.md stage 1: compute NP, IF, TSS and the 16 mean-maximal power
 * values for every matched power ride of the listed athletes and write them next to the
 * GoldenCheetah metadata values as JSON lines. The ride index comes from
 * `python -m ride_insight_ml.rides`; the analysis is `python -m ride_insight_ml.parity`.
 *
 * Usage: --zips <dir> --index <dir> --athletes <file: one id per line> --out <jsonl>
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    val zips = File(options.getValue("--zips"))
    val index = File(options.getValue("--index"))
    val athletes = File(options.getValue("--athletes")).readLines().map { it.trim() }.filter { it.isNotEmpty() }
    val out = File(options.getValue("--out"))
    out.parentFile?.mkdirs()

    val mapper = JsonMapper.builder().build()
    var rides = 0
    out.bufferedWriter().use { writer ->
        for (athlete in athletes) {
            val indexFile = File(index, "$athlete.json")
            val zipFile = File(zips, "$athlete.zip")
            if (!indexFile.exists() || !zipFile.exists()) {
                System.err.println("skip $athlete: missing index or zip")
                continue
            }
            val tree = mapper.readTree(indexFile)
            ZipFile(zipFile).use { zip ->
                for (ride in tree.path("rides")) {
                    val csv = ride.path("csv").asString(null) ?: continue
                    if (!ride.path("power_flag").asBoolean(false)) continue
                    val entry = zip.getEntry(csv) ?: continue
                    val parsed = zip.getInputStream(entry).bufferedReader().use { OpenDataCsv.read(it) }
                    val line = compare(athlete, ride, csv, parsed)
                    writer.write(mapper.writeValueAsString(line))
                    writer.newLine()
                    rides++
                }
            }
            System.err.println("$athlete done ($rides rides so far)")
        }
    }
    println("rides=$rides out=${out.path}")
}

internal fun compare(athlete: String, ride: JsonNode, csv: String, parsed: OpenDataCsv.Result): Map<String, Any?> {
    val samples = parsed.samples
    val recInt = RecordingInterval.estimate(samples.secs)
    val gc = ride.path("gc")
    val cp = gc.path("cp_setting").asDouble(0.0).takeIf { it > 0.0 }

    val np = recInt?.let { PowerMetrics.normalizedPower(samples.power, it) }
    val intensity = np?.let { PowerMetrics.intensityFactor(it, cp) }
    val tss = if (np != null && recInt != null) PowerMetrics.trainingStressScore(np, samples.size, recInt, cp) else null
    val mmp = LinkedHashMap<String, Double?>()
    for (window in MeanMaximalPower.STANDARD_WINDOWS_SECS) {
        mmp[MeanMaximalPower.metricKey(window)] =
            recInt?.let { MeanMaximalPower.peakPower(samples, it, window.toDouble()) }
    }
    val over3000 = samples.power.count { it >= 3000.0 }
    val negative = samples.power.count { it < 0.0 }

    return linkedMapOf(
        "athlete" to athlete,
        "date" to ride.path("date").asString(null),
        "csv" to csv,
        "rec_int" to recInt,
        "samples" to samples.size,
        "last_secs" to (samples.secs.lastOrNull()),
        "power_missing" to samples.powerMissing,
        "bad_rows" to parsed.badRows,
        "over_3000" to over3000,
        "negative" to negative,
        "cp" to cp,
        "ours" to linkedMapOf("np" to np, "if" to intensity, "tss" to tss, "mmp" to mmp),
        "gc" to linkedMapOf(
            "np" to gc.path("coggan_np").nullableDouble(),
            "if" to gc.path("coggan_if").nullableDouble(),
            "tss" to gc.path("coggan_tss").nullableDouble(),
            "average_power" to gc.path("average_power").nullableDouble(),
            "workout_time" to gc.path("workout_time").nullableDouble(),
            "mmp" to MeanMaximalPower.STANDARD_WINDOWS_SECS.associate { w ->
                val key = MeanMaximalPower.metricKey(w)
                key to gc.path(key).nullableDouble()
            },
        ),
    )
}

private fun JsonNode.nullableDouble(): Double? = if (isNull || isMissingNode) null else asDouble()

private fun parseArgs(args: Array<String>): Map<String, String> {
    val result = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(key.startsWith("--") && i + 1 < args.size) { "expected --option value, got '$key'" }
        result[key] = args[i + 1]
        i += 2
    }
    for (required in listOf("--zips", "--index", "--athletes", "--out")) {
        require(required in result) { "missing $required" }
    }
    return result
}
