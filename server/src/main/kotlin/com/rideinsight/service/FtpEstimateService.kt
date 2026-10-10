package com.rideinsight.service

import com.rideinsight.metrics.FtpRules
import java.time.LocalDate
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * FTP estimate for an athlete from the last [weeks] weeks. The served estimator is chosen by the
 * stage-3 measurement (docs/experiments.md): until a model is adopted, the estimate is R1
 * (95 % of the best 20-minute power) and the other rules are returned as context.
 */
@Service
class FtpEstimateService(private val athletes: AthleteService) {
    companion object {
        /** Stage-3 numbers for rule R1 (reports/ftp/dev.md and test.md, 2026-10-10). The registered
         * serving rule asks for the dev MAE; the sealed test figure is given as well. */
        const val DEV_MAE_WATTS = 31
        const val SEALED_TEST_MAE_WATTS = 29
        const val SEALED_TEST_BIAS_WATTS = 17
    }

    class Estimate(
        val method: String,
        val ftpWatts: Double?,
        val rules: Map<String, Double?>,
        val criticalPowerWPrimeKj: Double?,
        val windowsUsed: Int,
        val ridesInWindow: Int,
        val best20m: Double?,
        val best60m: Double?,
        val note: String,
    )

    fun estimate(athleteId: UUID, weeks: Int, asOf: LocalDate): Estimate {
        val points = athletes.powerCurve(athleteId, weeks, asOf)
        val rides = athletes.powerRideCount(athleteId, weeks, asOf)
        val best = points.toMap()
        val r1 = FtpRules.twentyMinute(best)
        val r2 = FtpRules.sixtyMinute(best)
        val cp = FtpRules.criticalPower(best)
        return Estimate(
            method = "R1",
            ftpWatts = r1,
            rules = linkedMapOf("R1_20min_x0.95" to r1, "R2_60min" to r2, "R3_critical_power" to cp?.first),
            criticalPowerWPrimeKj = cp?.second?.div(1000.0),
            windowsUsed = best.size,
            ridesInWindow = rides,
            best20m = best[1200],
            best60m = best[3600],
            note = if (r1 == null) {
                "no ride of 20 minutes or more with power in the last $weeks weeks"
            } else {
                "rule R1 = 0.95 × best 20-minute power over $rides rides with power in the last $weeks weeks. " +
                    "Against athletes' own FTP settings R1 was off by $DEV_MAE_WATTS W on average (dev set) and " +
                    "$SEALED_TEST_MAE_WATTS W on the sealed test set, running about $SEALED_TEST_BIAS_WATTS W low; " +
                    "a learned model was not adopted (docs/experiments.md stage 3)"
            },
        )
    }
}
