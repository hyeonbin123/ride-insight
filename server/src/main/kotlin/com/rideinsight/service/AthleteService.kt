package com.rideinsight.service

// [파일 역할] 선수 단위 업무를 맡는 서비스다: 선수 생성·조회, FTP 이력 관리와 변경 시 지표 재계산, 훈련
// 부하(PMC), 기간 파워 커브, 기간 안 파워 라이딩 수.
// [연결] AthleteController와 FtpEstimateService가 부른다. 저장은 AthleteRepository·ActivityRepository,
// 재계산은 MetricsService, PMC 계산은 metrics.TrainingLoad가 한다. 날짜는 모두 UTC 기준이다.

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
    // 선수를 만들고, FTP가 주어지면 함께 등록한다. 한 트랜잭션이라 FTP 저장이 실패하면 선수도 남지 않는다.
    @Transactional
    fun create(name: String, gender: String?, birthYear: Int?, weightKg: Double?, ftp: Int?): Pair<Athlete, Threshold?> {
        val athlete = athletes.insert(name, gender, birthYear, weightKg)
        var threshold: Threshold? = null
        if (ftp != null) {
            // 적용일을 충분히 이른 날짜로 두어 과거 날짜의 활동을 올려도 이 FTP가 적용되게 한다. 나중에
            // 날짜를 지정한 FTP가 등록되면 그 날짜부터는 그 값이 우선한다(thresholdOn은 가장 최근 적용일을
            // 고른다).
            val from = LocalDate.of(1990, 1, 1) // 날짜를 지정한 FTP가 생기기 전까지 모든 활동에 적용된다
            athletes.setThreshold(athlete.id, from, ftp, "manual")
            threshold = Threshold(from, ftp, "manual")
        }
        return athlete to threshold
    }

    // 선수와 오늘(UTC) 기준 유효한 FTP. 없는 선수는 404.
    fun get(id: UUID): Pair<Athlete, Threshold?> {
        val athlete = athletes.find(id) ?: throw NotFoundException("athlete $id not found")
        return athlete to athletes.thresholdOn(id, LocalDate.now(ZoneOffset.UTC))
    }

    // FTP 이력 전체(없는 선수 404).
    fun thresholds(id: UUID): List<Threshold> {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        return athletes.thresholds(id)
    }

    // FTP 변경: 선수 행을 잠근 뒤(같은 선수의 업로드와 겹치지 않게) 이력을 넣거나 같은 날짜 값을 덮어쓰고, 그
    // 날짜 0시(UTC) 이후 시작한 활동의 지표를 다시 계산한다. 재계산은 활동 날짜마다 유효한 FTP를 다시
    // 찾으므로, 더 뒤 날짜의 FTP가 있는 활동은 그 값이 그대로 쓰인다. 다시 계산한 활동 수를 돌려준다.
    /** 어떤 날짜부터의 FTP를 정하고, 그 날짜 이후 활동의 TSS를 다시 계산한다. */
    @Transactional
    fun setThreshold(id: UUID, effectiveFrom: LocalDate, ftpWatts: Int): Int {
        if (!athletes.lock(id)) throw NotFoundException("athlete $id not found")
        athletes.setThreshold(id, effectiveFrom, ftpWatts, "manual")
        return metrics.recomputeFrom(id, effectiveFrom.atStartOfDay().atOffset(ZoneOffset.UTC))
    }

    // PMC 시계열. 범위는 to ≥ from이고 길이는 3 × 366일 이하로 제한한다. 어기면 require가 400으로 이어진다.
    fun trainingLoad(id: UUID, from: LocalDate, to: LocalDate): List<TrainingLoad.Day> {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        require(!to.isBefore(from)) { "'to' must not be before 'from'" }
        require(java.time.temporal.ChronoUnit.DAYS.between(from, to) <= 3 * 366) { "range must be at most three years" }
        // 부하는 지수 가중 평균이라 조회 시작일 전의 훈련이 남아 있어야 한다. CTL이 조회 시작일에 좌우되지
        // 않도록 선수의 첫 활동부터 조회 전날까지 먼저 계산(워밍업)해 그 마지막 값을 초기값으로 넘긴다.
        // 처음에는 120일만 워밍업해 CTL의 약 6%가 빠졌고, 조회 시작일에 따라 CTL이 최대 6% 달라졌다
        // (2026-10-10 Codex 2차 검토 지적 4).
        val firstDay = activities.firstActivityDate(id)
        val warmFrom = if (firstDay != null && firstDay.isBefore(from)) firstDay else from
        // 날짜별 TSS 합은 워밍업 시작일부터 to까지 한 번에 읽는다.
        val daily = activities.dailyTss(id, warmFrom, to)
        val warm = if (warmFrom.isBefore(from)) TrainingLoad.series(warmFrom, from.minusDays(1), daily).lastOrNull() else null
        return TrainingLoad.series(from, to, daily, warm?.ctl ?: 0.0, warm?.atl ?: 0.0)
    }

    // FTP 추정 근거인 '창 안 파워 라이딩 수'. 기간은 [asOf 다음 날 0시 − weeks주, asOf 다음 날 0시)라 asOf
    // 당일을 포함한다(docs/experiments.md 규칙 변경 7).
    /** [asOf]까지(당일 포함) 최근 [weeks]주 동안의 파워 라이딩 수. */
    fun powerRideCount(id: UUID, weeks: Int, asOf: LocalDate): Int {
        val to = asOf.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
        return activities.countPowerActivities(id, to.minusWeeks(weeks.toLong()), to)
    }

    // 기간 최대 파워 커브: 기간 안 활동들에 저장된 16구간 MMP의 구간별 최댓값. 기간은 반열린 구간 [from,
    // to)라 다음 날 0시는 포함하지 않는다(Codex 2차 검토 지적 7: 처음에는 between이 다음 날 자정을 포함했다).
    // weeks는 1~104주만 받는다.
    /** [asOf]까지(당일 포함) 최근 [weeks]주 동안 표준 구간마다의 최대 파워. */
    fun powerCurve(id: UUID, weeks: Int, asOf: LocalDate): List<Pair<Int, Double>> {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        require(weeks in 1..104) { "weeks must be between 1 and 104" }
        val to = asOf.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
        val from = to.minusWeeks(weeks.toLong())
        val best = activities.maxMmp(id, from, to)
        // 표준 구간 순서대로, 값이 있고 0보다 큰 것만 (초, W) 쌍으로 돌려준다. 3000 W 가드로 0이 된 값은
        // 여기서 빠진다.
        return MeanMaximalPower.STANDARD_WINDOWS_SECS.toList().mapNotNull { w ->
            best[MeanMaximalPower.metricKey(w)]?.takeIf { it > 0 }?.let { w to it }
        }
    }
}
