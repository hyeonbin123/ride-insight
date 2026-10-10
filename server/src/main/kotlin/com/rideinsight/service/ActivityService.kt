package com.rideinsight.service

import com.rideinsight.fit.FitActivity
import com.rideinsight.fit.FitProfile
import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.storage.ActivityRepository
import com.rideinsight.storage.ActivityRow
import com.rideinsight.storage.AthleteRepository
import com.rideinsight.storage.MetricsRow
import com.rideinsight.storage.SampleSeries
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class StoredActivity(val activity: ActivityRow, val metrics: MetricsRow?)

@Service
class ActivityService(
    private val athletes: AthleteRepository,
    private val activities: ActivityRepository,
    private val metrics: MetricsService,
) {
    companion object {
        /** Sports we refuse: the metrics here are defined for cycling power only. */
        val REJECTED_SPORTS: Map<Long, String> = mapOf(
            FitProfile.Sport.RUNNING to "running",
            5L to "swimming",
            11L to "walking",
            17L to "hiking",
        )
        const val MAX_POWER_STORED = 32_000 // smallint column; anything above is treated as missing
        const val MAX_CURVE_POINTS = 300
    }

    @Transactional
    fun ingestFit(athleteId: UUID, bytes: ByteArray, fileName: String?): StoredActivity {
        if (!athletes.lock(athleteId)) throw NotFoundException("athlete $athleteId not found")
        val fit = FitActivity.decode(bytes) // FitDecodeException -> 400
        val sport = fit.sport
        REJECTED_SPORTS[sport]?.let { throw UnsupportedSportException("sport '$it' is not supported; upload a cycling activity") }
        if (fit.records.isEmpty()) throw InvalidActivityException("the file has no record messages")

        val first = fit.records.first().timestampUnix
        val secs = fit.records.map { (it.timestampUnix - first).toDouble() }
        for (i in 1 until secs.size) {
            if (secs[i] < secs[i - 1]) throw InvalidActivityException("record timestamps go backwards at sample $i")
        }
        val power = fit.records.map { r -> r.power?.takeIf { it in 0..MAX_POWER_STORED } }
        val hasPower = power.any { it != null }
        val series = SampleSeries(
            secs = secs,
            power = if (hasPower) power else null,
            heartRate = fit.records.map { it.heartRate }.takeIf { l -> l.any { it != null } },
            cadence = fit.records.map { it.cadence }.takeIf { l -> l.any { it != null } },
            distanceM = fit.records.map { it.distanceMeters }.takeIf { l -> l.any { it != null } },
            altitudeM = fit.records.map { it.altitudeMeters }.takeIf { l -> l.any { it != null } },
        )
        val interval = RecordingInterval.analyse(secs.toDoubleArray())
        val startedAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(first), ZoneOffset.UTC)
        val id = activities.insert(
            ActivityRepository.NewActivity(
                athleteId = athleteId,
                startedAt = startedAt,
                source = "fit",
                sourceName = fileName?.take(200),
                sport = FitProfile.sportName(sport),
                recIntSecs = interval?.intervalSecs,
                intervalShare = interval?.modeShare,
                sampleCount = secs.size,
                durationSecs = secs.last() + (interval?.intervalSecs ?: 0.0),
                manufacturer = fit.fileId?.manufacturer?.toInt(),
                product = fit.fileId?.product?.toInt(),
                crcValid = fit.crcValid,
                hasPower = hasPower,
                powerMissing = power.count { it == null },
            ),
            series,
        )
        val row = metrics.computeAndStore(id, athleteId, startedAt, series, hasPower)
        return StoredActivity(activities.find(id)!!, row)
    }

    fun get(id: UUID): StoredActivity {
        val a = activities.find(id) ?: throw NotFoundException("activity $id not found")
        return StoredActivity(a, loadMetrics(id))
    }

    fun list(athleteId: UUID, before: OffsetDateTime?, limit: Int): List<StoredActivity> {
        if (!athletes.exists(athleteId)) throw NotFoundException("athlete $athleteId not found")
        val rows = activities.list(athleteId, before, limit.coerceIn(1, 200))
        val all = activities.metricsFor(rows.map { it.id })
        return rows.map { r -> StoredActivity(r, all[r.id]?.let { (m, json) -> withMmp(m, json) }) }
    }

    fun samples(id: UUID): SampleSeries {
        if (activities.find(id) == null) throw NotFoundException("activity $id not found")
        return activities.samples(id) ?: throw NotFoundException("activity $id has no samples")
    }

    /** Full mean-maximal curve of one activity on a log-spaced grid of durations (at most ~300 points). */
    fun powerCurve(id: UUID): Pair<List<Pair<Int, Double>>, Map<String, Double?>> {
        val a = activities.find(id) ?: throw NotFoundException("activity $id not found")
        val series = activities.samples(id) ?: throw NotFoundException("activity $id has no samples")
        val stored = loadMetrics(id)?.mmp ?: emptyMap()
        if (!a.hasPower) return emptyList<Pair<Int, Double>>() to stored
        val rs = metrics.rideSamples(series)
        val recInt = RecordingInterval.estimate(rs.secs) ?: return emptyList<Pair<Int, Double>>() to stored
        val maxSecs = (rs.secs.last() + recInt).toInt()
        val points = ArrayList<Pair<Int, Double>>()
        for (d in curveDurations(maxSecs)) {
            val w = MeanMaximalPower.best(rs, recInt, d.toDouble()) ?: continue
            points.add(d to w)
        }
        return points to stored
    }

    fun delete(id: UUID) {
        if (!activities.delete(id)) throw NotFoundException("activity $id not found")
    }

    private fun loadMetrics(id: UUID): MetricsRow? = activities.metrics(id)?.let { (m, json) -> withMmp(m, json) }

    private fun withMmp(m: MetricsRow, json: String) = MetricsRow(
        m.metricsVersion, m.ftpUsed, m.normalizedPower, m.intensityFactor, m.tss, m.averagePower, m.maxPower, m.workKj,
        metrics.parseMmp(json),
    )

    /** Log-spaced durations up to [maxSecs], never more than [MAX_CURVE_POINTS] of them. */
    internal fun curveDurations(maxSecs: Int): List<Int> {
        var growth = 1.04
        while (true) {
            val out = sortedSetOf<Int>()
            var d = 1.0
            while (d <= maxSecs) {
                out.add(d.toInt())
                d = maxOf(d + 1, d * growth)
            }
            for (w in MeanMaximalPower.STANDARD_WINDOWS_SECS) if (w <= maxSecs) out.add(w)
            if (maxSecs >= 1) out.add(maxSecs)
            if (out.size <= MAX_CURVE_POINTS) return out.toList()
            growth *= 1.5 // degenerate rides (e.g. two samples a week apart) need a coarser grid
        }
    }
}
