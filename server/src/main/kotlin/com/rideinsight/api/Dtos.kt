package com.rideinsight.api

import com.rideinsight.service.StoredActivity
import com.rideinsight.storage.Athlete
import com.rideinsight.storage.MetricsRow
import com.rideinsight.storage.SampleSeries
import com.rideinsight.storage.Threshold
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class CreateAthleteRequest(
    @NotBlank @Size(max = 100) val name: String,
    @Pattern(regexp = "M|F") val gender: String? = null,
    @Min(1900) @Max(2100) val birthYear: Int? = null,
    val weightKg: Double? = null,
    @Min(50) @Max(1000) val ftp: Int? = null,
)

data class ThresholdRequest(
    val effectiveFrom: LocalDate,
    @Min(50) @Max(1000) val ftpWatts: Int,
)

data class ThresholdResponse(val effectiveFrom: LocalDate, val ftpWatts: Int, val source: String) {
    companion object {
        fun from(t: Threshold) = ThresholdResponse(t.effectiveFrom, t.ftpWatts, t.source)
    }
}

data class AthleteResponse(
    val id: UUID,
    val name: String,
    val gender: String?,
    val birthYear: Int?,
    val weightKg: Double?,
    val createdAt: OffsetDateTime,
    val currentThreshold: ThresholdResponse?,
) {
    companion object {
        fun from(a: Athlete, t: Threshold?) =
            AthleteResponse(a.id, a.name, a.gender, a.birthYear, a.weightKg, a.createdAt, t?.let(ThresholdResponse::from))
    }
}

data class DeviceResponse(val manufacturer: Int?, val product: Int?)

data class MetricsResponse(
    val ftpUsed: Int?,
    val normalizedPower: Double?,
    val intensityFactor: Double?,
    val tss: Double?,
    val averagePower: Double?,
    val maxPower: Int?,
    val workKj: Double?,
    val mmp: Map<String, Double?>,
) {
    companion object {
        fun from(m: MetricsRow) =
            MetricsResponse(m.ftpUsed, m.normalizedPower, m.intensityFactor, m.tss, m.averagePower, m.maxPower, m.workKj, m.mmp)
    }
}

data class ActivityResponse(
    val id: UUID,
    val athleteId: UUID,
    val startedAt: OffsetDateTime,
    val source: String,
    val sourceName: String?,
    val sport: String?,
    val recIntSecs: Double?,
    val intervalShare: Double?,
    val sampleCount: Int,
    val durationSecs: Double?,
    val device: DeviceResponse,
    val crcValid: Boolean?,
    val hasPower: Boolean,
    val powerMissing: Int,
    val metrics: MetricsResponse?,
) {
    companion object {
        fun from(s: StoredActivity): ActivityResponse {
            val a = s.activity
            return ActivityResponse(
                a.id, a.athleteId, a.startedAt, a.source, a.sourceName, a.sport, a.recIntSecs, a.intervalShare,
                a.sampleCount, a.durationSecs, DeviceResponse(a.manufacturer, a.product), a.crcValid, a.hasPower,
                a.powerMissing, s.metrics?.let(MetricsResponse::from),
            )
        }
    }
}

data class ActivityListResponse(val items: List<ActivityResponse>, val nextBefore: OffsetDateTime?)

data class SamplesResponse(
    val every: Int,
    val secs: List<Double>,
    val power: List<Int?>?,
    val heartRate: List<Int?>?,
    val cadence: List<Int?>?,
    val distanceM: List<Double?>?,
    val altitudeM: List<Double?>?,
) {
    companion object {
        /** One point per [every] seconds of elapsed time: the first sample of each bucket. */
        fun from(s: SampleSeries, every: Int): SamplesResponse {
            val keep = ArrayList<Int>()
            var lastBucket = Long.MIN_VALUE
            for ((i, t) in s.secs.withIndex()) {
                val bucket = Math.floor(t / every).toLong()
                if (bucket != lastBucket) {
                    keep.add(i)
                    lastBucket = bucket
                }
            }
            fun <T> pick(list: List<T>?): List<T>? = list?.let { l -> keep.map { l[it] } }
            return SamplesResponse(
                every, pick(s.secs)!!, pick(s.power), pick(s.heartRate), pick(s.cadence), pick(s.distanceM), pick(s.altitudeM),
            )
        }
    }
}

data class CurvePoint(val secs: Int, val watts: Double)

data class PowerCurveResponse(val points: List<CurvePoint>, val standard: Map<String, Double?>)

data class TrainingLoadDay(val date: LocalDate, val tss: Double, val ctl: Double, val atl: Double, val tsb: Double)

data class TrainingLoadResponse(val days: List<TrainingLoadDay>)

data class ErrorResponse(val error: String, val message: String)

data class FtpEstimateResponse(
    val method: String,
    val ftpWatts: Double?,
    val rules: Map<String, Double?>,
    val criticalPowerWPrimeKj: Double?,
    val windowsUsed: Int,
    val best20m: Double?,
    val best60m: Double?,
    val weeks: Int,
    val note: String,
)
