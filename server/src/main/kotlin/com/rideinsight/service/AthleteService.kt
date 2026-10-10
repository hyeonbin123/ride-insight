package com.rideinsight.service

import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.TrainingLoad
import com.rideinsight.storage.ActivityRepository
import com.rideinsight.storage.Athlete
import com.rideinsight.storage.AthleteRepository
import com.rideinsight.storage.Threshold
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AthleteService(
    private val athletes: AthleteRepository,
    private val activities: ActivityRepository,
    private val metrics: MetricsService,
) {
    @Transactional
    fun create(name: String, gender: String?, birthYear: Int?, weightKg: Double?, ftp: Int?): Pair<Athlete, Threshold?> {
        val athlete = athletes.insert(name, gender, birthYear, weightKg)
        var threshold: Threshold? = null
        if (ftp != null) {
            val from = LocalDate.of(1990, 1, 1) // applies to every activity until a dated threshold is set
            athletes.setThreshold(athlete.id, from, ftp, "manual")
            threshold = Threshold(from, ftp, "manual")
        }
        return athlete to threshold
    }

    fun get(id: UUID): Pair<Athlete, Threshold?> {
        val athlete = athletes.find(id) ?: throw NotFoundException("athlete $id not found")
        return athlete to athletes.thresholdOn(id, LocalDate.now(ZoneOffset.UTC))
    }

    fun thresholds(id: UUID): List<Threshold> {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        return athletes.thresholds(id)
    }

    /** Sets the FTP from a date and recomputes the TSS of activities from that date on. */
    @Transactional
    fun setThreshold(id: UUID, effectiveFrom: LocalDate, ftpWatts: Int): Int {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        athletes.setThreshold(id, effectiveFrom, ftpWatts, "manual")
        return metrics.recomputeFrom(id, effectiveFrom.atStartOfDay().atOffset(ZoneOffset.UTC))
    }

    fun trainingLoad(id: UUID, from: LocalDate, to: LocalDate): List<TrainingLoad.Day> {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        require(!to.isBefore(from)) { "'to' must not be before 'from'" }
        require(java.time.temporal.ChronoUnit.DAYS.between(from, to) <= 3 * 366) { "range must be at most three years" }
        // Warm up the loads from activities before 'from' so the series does not start at zero.
        val warmFrom = from.minusDays(120)
        val daily = activities.dailyTss(id, warmFrom, to)
        val warm = TrainingLoad.series(warmFrom, from.minusDays(1), daily).lastOrNull()
        return TrainingLoad.series(from, to, daily, warm?.ctl ?: 0.0, warm?.atl ?: 0.0)
    }

    /** Best power per standard window over the last [weeks] weeks before [asOf] (inclusive). */
    fun powerCurve(id: UUID, weeks: Int, asOf: LocalDate): List<Pair<Int, Double>> {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        require(weeks in 1..104) { "weeks must be between 1 and 104" }
        val to = asOf.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
        val from = to.minusWeeks(weeks.toLong())
        val best = activities.maxMmp(id, from, to)
        return MeanMaximalPower.STANDARD_WINDOWS_SECS.toList().mapNotNull { w ->
            best[MeanMaximalPower.metricKey(w)]?.takeIf { it > 0 }?.let { w to it }
        }
    }
}
