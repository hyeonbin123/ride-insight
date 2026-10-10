package com.rideinsight.service

import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.PowerMetrics
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.metrics.RideSamples
import com.rideinsight.storage.ActivityRepository
import com.rideinsight.storage.AthleteRepository
import com.rideinsight.storage.MetricsRow
import com.rideinsight.storage.SampleSeries
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

/** Computes and stores per-activity metrics (NP, IF, TSS, MMP, averages) from stored samples. */
@Service
class MetricsService(
    private val athletes: AthleteRepository,
    private val activities: ActivityRepository,
    private val mapper: JsonMapper,
) {
    companion object {
        /** Bump when the metric code changes so stored rows can be recomputed. */
        const val METRICS_VERSION = 1
    }

    fun rideSamples(series: SampleSeries): RideSamples {
        val power = series.power
        val missing = power?.count { it == null } ?: series.secs.size
        val watts = DoubleArray(series.secs.size) { i -> (power?.get(i) ?: 0).toDouble() }
        return RideSamples(series.secs.toDoubleArray(), watts, missing)
    }

    /** Computes metrics for one activity and writes them; returns the row (null when no power). */
    fun computeAndStore(activityId: UUID, athleteId: UUID, startedAt: OffsetDateTime, series: SampleSeries, hasPower: Boolean): MetricsRow? {
        if (!hasPower) return null
        val row = compute(athleteId, startedAt, series)
        activities.saveMetrics(activityId, row, mapper.writeValueAsString(row.mmp))
        return row
    }

    fun compute(athleteId: UUID, startedAt: OffsetDateTime, series: SampleSeries): MetricsRow {
        val samples = rideSamples(series)
        val interval = RecordingInterval.analyse(samples.secs)
        val recInt = interval?.intervalSecs
        val ftp = athletes.thresholdOn(athleteId, startedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate())?.ftpWatts
        val np = recInt?.let { PowerMetrics.normalizedPower(samples.power, it) }
        val intensity = np?.let { PowerMetrics.intensityFactor(it, ftp?.toDouble()) }
        val tss = if (np != null && recInt != null) {
            PowerMetrics.trainingStressScore(np, samples.size, recInt, ftp?.toDouble())
        } else {
            null
        }
        val mmp = LinkedHashMap<String, Double?>()
        for (w in MeanMaximalPower.STANDARD_WINDOWS_SECS) {
            mmp[MeanMaximalPower.metricKey(w)] = recInt?.let { MeanMaximalPower.peakPower(samples, it, w.toDouble()) }
        }
        val present = series.power?.filterNotNull() ?: emptyList()
        return MetricsRow(
            metricsVersion = METRICS_VERSION,
            ftpUsed = ftp,
            normalizedPower = np,
            intensityFactor = intensity,
            tss = tss,
            averagePower = if (samples.size > 0) samples.power.average() else null,
            maxPower = present.maxOrNull(),
            workKj = recInt?.let { samples.power.sum() * it / 1000.0 },
            mmp = mmp,
        )
    }

    /** Recomputes every activity of the athlete that started on or after [from] (after an FTP change). */
    fun recomputeFrom(athleteId: UUID, from: OffsetDateTime): Int {
        var n = 0
        for (a in activities.listFrom(athleteId, from)) {
            if (!a.hasPower) continue
            val series = activities.samples(a.id) ?: continue
            computeAndStore(a.id, athleteId, a.startedAt, series, true)
            n++
        }
        return n
    }

    fun parseMmp(json: String): Map<String, Double?> {
        @Suppress("UNCHECKED_CAST")
        val raw = mapper.readValue(json, Map::class.java) as Map<String, Any?>
        return raw.mapValues { (_, v) -> (v as Number?)?.toDouble() }
    }
}
