package com.rideinsight.tools

import com.rideinsight.metrics.OpenDataCsv
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.service.MetricsService
import com.rideinsight.storage.ActivityRepository
import com.rideinsight.storage.AthleteRepository
import com.rideinsight.storage.DuplicateActivityException
import com.rideinsight.storage.SampleSeries
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipFile
import org.springframework.boot.runApplication
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Imports one GoldenCheetah OpenData athlete into the database: matched power rides become
 * activities, and changes of the chosen FTP label become dated thresholds.
 *
 * Usage: ./gradlew -q importOpenData --args="--zip <athlete.zip> --index <ride index json> --name <athlete name> [--label cp|ftp] [--limit N]"
 * The database settings come from application.properties / RIDE_INSIGHT_DB_* variables.
 */
fun main(args: Array<String>) {
    val options = parseOptions(args, required = listOf("--zip", "--index", "--name"))
    val context = runApplication<com.rideinsight.ServerApplication>("--spring.main.web-application-type=none") {
        setLogStartupInfo(false)
    }
    try {
        val athletes = context.getBean(AthleteRepository::class.java)
        val activities = context.getBean(ActivityRepository::class.java)
        val metrics = context.getBean(MetricsService::class.java)
        val result = importAthlete(
            File(options.getValue("--zip")),
            File(options.getValue("--index")),
            options.getValue("--name"),
            options["--label"] ?: "cp",
            options["--limit"]?.toInt(),
            athletes,
            activities,
            metrics,
        )
        println("athlete=${result.athleteId} activities=${result.activities} skipped=${result.skipped} thresholds=${result.thresholds}")
    } finally {
        context.close()
    }
}

class ImportResult(val athleteId: UUID, val activities: Int, val skipped: Int, val thresholds: Int)

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

fun importAthlete(
    zip: File,
    index: File,
    name: String,
    label: String,
    limit: Int?,
    athletes: AthleteRepository,
    activities: ActivityRepository,
    metrics: MetricsService,
): ImportResult {
    require(label == "cp" || label == "ftp") { "--label must be cp or ftp" }
    val tree = JsonMapper.builder().build().readTree(index)
    val athlete = athletes.insert(
        name,
        tree.path("gender").asString(null)?.takeIf { it == "M" || it == "F" },
        tree.path("yob").asString(null)?.toIntOrNull(),
        null,
    )
    val rides = tree.path("rides").filter { it.path("csv").asString(null) != null && it.path("power_flag").asBoolean(false) }
        .sortedBy { it.path("date").asString() }

    // Thresholds first, so TSS can use the FTP in force on each ride's date.
    var thresholds = 0
    var last: Int? = null
    for (ride in rides) {
        val value = labelOf(ride, label) ?: continue
        if (value != last) {
            val date = LocalDateTime.parse(ride.path("date").asString().removeSuffix(" UTC"), DATE_FORMAT).toLocalDate()
            athletes.setThreshold(athlete.id, date, value, "import")
            thresholds++
            last = value
        }
    }

    var stored = 0
    var skipped = 0
    ZipFile(zip).use { z ->
        for (ride in rides) {
            if (limit != null && stored >= limit) break
            val csv = ride.path("csv").asString()
            val entry = z.getEntry(csv) ?: run { skipped++; null } ?: continue
            val parsed = z.getInputStream(entry).bufferedReader().use { OpenDataCsv.read(it) }
            val samples = parsed.samples
            if (samples.size < 2) { skipped++; continue }
            val interval = RecordingInterval.analyse(samples.secs)
            val startedAt = LocalDateTime.parse(ride.path("date").asString().removeSuffix(" UTC"), DATE_FORMAT).atOffset(ZoneOffset.UTC)
            val power = samples.power.mapIndexed { i, w -> if (i < samples.size) w.toInt() else null }
            val series = SampleSeries(
                secs = samples.secs.toList(),
                power = power,
                heartRate = parsed.extras.heartRate,
                cadence = parsed.extras.cadence,
                distanceM = parsed.extras.distanceM,
                altitudeM = parsed.extras.altitudeM,
            )
            val id = try {
                activities.insert(
                    ActivityRepository.NewActivity(
                        athleteId = athlete.id,
                        startedAt = startedAt,
                        source = "opendata",
                        sourceName = csv,
                        sport = "cycling",
                        recIntSecs = interval?.intervalSecs,
                        intervalShare = interval?.modeShare,
                        sampleCount = samples.size,
                        durationSecs = samples.secs.last() + (interval?.intervalSecs ?: 0.0),
                        manufacturer = null,
                        product = null,
                        crcValid = null,
                        hasPower = true,
                        powerMissing = samples.powerMissing,
                    ),
                    series,
                )
            } catch (e: DuplicateActivityException) {
                skipped++
                continue
            }
            metrics.computeAndStore(id, athlete.id, startedAt, series, true)
            stored++
        }
    }
    return ImportResult(athlete.id, stored, skipped, thresholds)
}

/** CP label = cp_setting; FTP label = the threshold GoldenCheetah used for IF (NP / IF), rounded. */
internal fun labelOf(ride: JsonNode, label: String): Int? {
    val gc = ride.path("gc")
    return if (label == "cp") {
        gc.path("cp_setting").asDouble(0.0).takeIf { it > 0 }?.let { Math.round(it).toInt() }
    } else {
        val np = gc.path("coggan_np").asDouble(0.0)
        val intensity = gc.path("coggan_if").asDouble(0.0)
        if (np > 0 && intensity > 0) Math.round(np / intensity).toInt() else null
    }
}

internal fun parseOptions(args: Array<String>, required: List<String>): Map<String, String> {
    val result = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(key.startsWith("--") && i + 1 < args.size) { "expected --option value, got '$key'" }
        result[key] = args[i + 1]
        i += 2
    }
    for (r in required) require(r in result) { "missing $r" }
    return result
}
