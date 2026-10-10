package com.rideinsight.api

import com.rideinsight.service.ActivityService
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/activities")
class ActivityController(private val activities: ActivityService) {
    companion object {
        const val MAX_POINTS = 20_000
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ActivityResponse = ActivityResponse.from(activities.get(id))

    @GetMapping("/{id}/samples")
    fun samples(@PathVariable id: UUID, @RequestParam(defaultValue = "1") every: Int): SamplesResponse {
        require(every >= 1) { "'every' must be at least 1" }
        val series = activities.samples(id)
        // Never return more than MAX_POINTS: widen the stride if the caller asked for too much.
        val stride = maxOf(every, (series.secs.size + MAX_POINTS - 1) / MAX_POINTS)
        return SamplesResponse.from(series, stride)
    }

    @GetMapping("/{id}/power-curve")
    fun powerCurve(@PathVariable id: UUID): PowerCurveResponse {
        val (points, standard) = activities.powerCurve(id)
        return PowerCurveResponse(points.map { (s, w) -> CurvePoint(s, w) }, standard)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        activities.delete(id)
        return ResponseEntity.noContent().build()
    }
}
