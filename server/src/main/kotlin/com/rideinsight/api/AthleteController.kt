package com.rideinsight.api

import com.rideinsight.service.ActivityService
import com.rideinsight.service.AthleteService
import com.rideinsight.service.FtpEstimateService
import com.rideinsight.service.InvalidActivityException
import jakarta.validation.Valid
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/athletes")
class AthleteController(
    private val athletes: AthleteService,
    private val activities: ActivityService,
    private val ftp: FtpEstimateService,
) {
    @PostMapping
    fun create(@Valid @RequestBody req: CreateAthleteRequest): ResponseEntity<AthleteResponse> {
        val (athlete, threshold) = athletes.create(req.name.trim(), req.gender, req.birthYear, req.weightKg, req.ftp)
        return ResponseEntity.created(URI.create("/api/athletes/${athlete.id}")).body(AthleteResponse.from(athlete, threshold))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): AthleteResponse {
        val (athlete, threshold) = athletes.get(id)
        return AthleteResponse.from(athlete, threshold)
    }

    @GetMapping("/{id}/thresholds")
    fun thresholds(@PathVariable id: UUID): List<ThresholdResponse> = athletes.thresholds(id).map(ThresholdResponse::from)

    @PutMapping("/{id}/threshold")
    fun setThreshold(@PathVariable id: UUID, @Valid @RequestBody req: ThresholdRequest): Map<String, Any> {
        val recomputed = athletes.setThreshold(id, req.effectiveFrom, req.ftpWatts)
        return mapOf("effectiveFrom" to req.effectiveFrom, "ftpWatts" to req.ftpWatts, "recomputedActivities" to recomputed)
    }

    @PostMapping("/{id}/activities", consumes = ["multipart/form-data"])
    fun upload(@PathVariable id: UUID, @RequestPart("file") file: MultipartFile): ResponseEntity<ActivityResponse> {
        if (file.isEmpty) throw InvalidActivityException("the uploaded file is empty")
        val stored = activities.ingestFit(id, file.bytes, file.originalFilename)
        return ResponseEntity.created(URI.create("/api/activities/${stored.activity.id}")).body(ActivityResponse.from(stored))
    }

    @GetMapping("/{id}/activities")
    fun list(
        @PathVariable id: UUID,
        @RequestParam(required = false) before: OffsetDateTime?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ActivityListResponse {
        val items = activities.list(id, before, limit).map(ActivityResponse::from)
        val next = if (items.size >= limit.coerceIn(1, 200)) items.last().startedAt else null
        return ActivityListResponse(items, next)
    }

    @GetMapping("/{id}/training-load")
    fun trainingLoad(
        @PathVariable id: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): TrainingLoadResponse {
        val end = to ?: LocalDate.now(ZoneOffset.UTC)
        val start = from ?: end.minusDays(90)
        return TrainingLoadResponse(
            athletes.trainingLoad(id, start, end).map { TrainingLoadDay(it.date, it.tss, it.ctl, it.atl, it.tsb) },
        )
    }

    @GetMapping("/{id}/ftp-estimate")
    fun ftpEstimate(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "6") weeks: Int,
        @RequestParam(required = false) asOf: LocalDate?,
    ): FtpEstimateResponse {
        val e = ftp.estimate(id, weeks, asOf ?: LocalDate.now(ZoneOffset.UTC))
        return FtpEstimateResponse(
            e.method, e.ftpWatts, e.rules, e.criticalPowerWPrimeKj, e.windowsUsed, e.ridesInWindow, e.best20m, e.best60m, weeks, e.note,
        )
    }

    @GetMapping("/{id}/power-curve")
    fun powerCurve(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "6") weeks: Int,
        @RequestParam(required = false) asOf: LocalDate?,
    ): PowerCurveResponse {
        val points = athletes.powerCurve(id, weeks, asOf ?: LocalDate.now(ZoneOffset.UTC))
        return PowerCurveResponse(
            points.map { (s, w) -> CurvePoint(s, w) },
            points.associate { (s, w) -> com.rideinsight.metrics.MeanMaximalPower.metricKey(s) to w },
        )
    }
}
