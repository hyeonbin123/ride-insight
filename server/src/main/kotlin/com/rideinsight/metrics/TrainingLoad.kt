package com.rideinsight.metrics

// [파일 역할] 훈련 부하 PMC(Performance Management Chart)를 계산한다: 날짜별 TSS 합으로 CTL(만성 부하, 42일),
// ATL(급성 부하, 7일), TSB(컨디션 = 어제 CTL − 어제 ATL)를 만든다.
// [설계 이유] Coggan PMC 표준 정의를 따른다(docs/plan.md 지표 정의). 활동이 없는 날도 TSS 0으로 넣어 부하가
// 줄어들게 한다. 순수 함수라 손으로 계산한 3일 예제 테스트로 검증한다(docs/api.md 완료 기준 4,
// TrainingLoadTest).
// [연결] AthleteService.trainingLoad가 선수의 첫 활동부터 워밍업한 초기값과 함께 부르고, 화면의 대시보드가 그
// 결과를 그린다.

import java.time.LocalDate
import kotlin.math.exp

/**
 * Performance Management Chart quantities (Coggan): CTL is a 42-day and ATL a 7-day exponentially
 * weighted average of daily TSS; TSB is yesterday's CTL minus yesterday's ATL. Days without an
 * activity count as TSS 0, so both loads decay.
 */
object TrainingLoad {
    // 시간 상수(일): CTL 42일, ATL 7일.
    const val CTL_DAYS = 42.0
    const val ATL_DAYS = 7.0

    // 하루 결과: 날짜, 그날 TSS 합, 그날 TSS를 반영한 CTL·ATL, 그날의 TSB(반영 전 값의 차).
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
        // 하루 갱신 계수 k = 1 − exp(−1 / 일수). 매일 load += (tss − load) × k로 지수 가중 평균을
        // 갱신한다(42일이면 k ≈ 0.0235, 7일이면 ≈ 0.133).
        val kCtl = 1.0 - exp(-1.0 / CTL_DAYS)
        val kAtl = 1.0 - exp(-1.0 / ATL_DAYS)
        var ctl = initialCtl
        var atl = initialAtl
        val out = ArrayList<Day>()
        var date = from
        while (!date.isAfter(to)) {
            // 그날 활동이 없으면 TSS 0이라 두 부하가 감쇠한다.
            val tss = dailyTss[date] ?: 0.0
            // TSB는 오늘 TSS를 반영하기 전(어제 끝) 값의 차다. 그래서 힘든 훈련 다음 날 음수가 된다.
            val tsb = ctl - atl // yesterday's values
            ctl += (tss - ctl) * kCtl
            atl += (tss - atl) * kAtl
            out.add(Day(date, tss, ctl, atl, tsb))
            date = date.plusDays(1)
        }
        return out
    }
}
