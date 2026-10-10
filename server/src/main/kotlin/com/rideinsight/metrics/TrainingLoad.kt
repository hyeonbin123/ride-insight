package com.rideinsight.metrics

import java.time.LocalDate
import kotlin.math.exp

/**
 * Performance Management Chart quantities (Coggan): CTL is a 42-day and ATL a 7-day exponentially
 * weighted average of daily TSS; TSB is yesterday's CTL minus yesterday's ATL. Days without an
 * activity count as TSS 0, so both loads decay.
 */
object TrainingLoad {
    const val CTL_DAYS = 42.0
    const val ATL_DAYS = 7.0

    class Day(val date: LocalDate, val tss: Double, val ctl: Double, val atl: Double, val tsb: Double)

    /**
     * Daily series from [from] to [to] inclusive. [dailyTss] maps a date to the sum of that day's
     * TSS; the loads start at [initialCtl]/[initialAtl] on the day before [from] (0 for a new athlete).
     * Update rule: `load_today = load_yesterday + (tss_today - load_yesterday) × (1 - exp(-1 / days))`.
     */
    fun series(
        from: LocalDate,
        to: LocalDate,
        dailyTss: Map<LocalDate, Double>,
        initialCtl: Double = 0.0,
        initialAtl: Double = 0.0,
    ): List<Day> {
        require(!to.isBefore(from)) { "to must not be before from" }
        val kCtl = 1.0 - exp(-1.0 / CTL_DAYS)
        val kAtl = 1.0 - exp(-1.0 / ATL_DAYS)
        var ctl = initialCtl
        var atl = initialAtl
        val out = ArrayList<Day>()
        var date = from
        while (!date.isAfter(to)) {
            val tss = dailyTss[date] ?: 0.0
            val tsb = ctl - atl // yesterday's values
            ctl += (tss - ctl) * kCtl
            atl += (tss - atl) * kAtl
            out.add(Day(date, tss, ctl, atl, tsb))
            date = date.plusDays(1)
        }
        return out
    }
}
