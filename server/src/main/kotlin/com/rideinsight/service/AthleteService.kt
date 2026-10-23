package com.rideinsight.service

// [파일 역할] 선수 단위 업무를 맡는 서비스다: 선수 생성·조회, FTP 이력 관리와 변경 시 지표 재계산, 훈련
// 부하(PMC), 기간 파워 커브, 기간 안 파워 라이딩 수.
// [연결] AthleteController와 FtpEstimateService가 부른다. 저장은 AthleteRepository·ActivityRepository,
// 재계산은 MetricsService, PMC 계산은 metrics.TrainingLoad가 한다. 날짜는 선수 시간대(기본 Asia/Seoul)의 현지
// 날짜로 센다(2026-10-15 전에는 UTC). 조회 요청은 tz 파라미터로 시간대를 바꿔 볼 수 있다([zone]).
// [시간대 해석은 Java 한 곳] 현지 날짜와 현지 0시 경계는 모두 이 서비스와 MetricsService가 java.time으로 계산하고, 저장소
// 쿼리에는 계산한 순간(OffsetDateTime)만 넘긴다. 2026-10-24 전에는 PMC의 날짜 묶음과 워밍업 시작일만 SQL `at time zone`이
// 셌는데, PostgreSQL이 고정 오프셋 이름 등을 Java와 다르게 읽어 PMC 날짜가 FTP 선택 날짜와 어긋날 수 있었다
// (ActivityRepository.tssByStart 주석).

import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.TrainingLoad
import com.rideinsight.storage.ActivityRepository
import com.rideinsight.storage.Athlete
import com.rideinsight.storage.AthleteRepository
import com.rideinsight.storage.Threshold
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
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
    fun create(name: String, gender: String?, birthYear: Int?, weightKg: Double?, ftp: Int?, timeZone: String? = null): Pair<Athlete, Threshold?> {
        // 시간대는 이름을 검사(400)한 뒤 Java가 정규화한 이름으로 저장한다. 없으면 기본값.
        val zone = timeZone?.let { parseTimeZone(it) }?.id ?: DEFAULT_TIME_ZONE
        val athlete = athletes.insert(name, gender, birthYear, weightKg, zone)
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

    // 선수와 오늘(현지 날짜) 기준 유효한 FTP. 없는 선수는 404. tz를 주면 그 시간대의 오늘을 쓴다.
    fun get(id: UUID, tz: String? = null): Pair<Athlete, Threshold?> {
        val athlete = athletes.find(id) ?: throw NotFoundException("athlete $id not found")
        return athlete to athletes.thresholdOn(id, LocalDate.now(zone(athlete, tz)))
    }

    /**
     * 조회에 쓸 시간대: 요청의 tz가 있으면 그것(검사해서 400), 없으면 선수의 시간대. 없는 선수는 404.
     * 요청 tz는 보기만 바꾼다. 저장된 TSS의 FTP 선택(활동 날짜)은 선수 시간대로 계산된 그대로다.
     */
    fun zone(id: UUID, tz: String?): ZoneId {
        val athlete = athletes.find(id) ?: throw NotFoundException("athlete $id not found")
        return zone(athlete, tz)
    }

    private fun zone(athlete: Athlete, tz: String?): ZoneId = tz?.let { parseTimeZone(it, "tz") } ?: ZoneId.of(athlete.timeZone)

    /**
     * 선수의 시간대를 바꾸고 모든 활동의 지표를 다시 계산한다(활동의 현지 날짜가 바뀌면 적용할 FTP가 바뀔 수 있다).
     * 선수 행을 잠가 업로드·FTP 변경과 겹치지 않게 한다. 정규화한 시간대 이름과 다시 계산한 활동 수를 돌려준다.
     */
    @Transactional
    fun setTimeZone(id: UUID, timeZone: String): Pair<String, Int> {
        val zone = parseTimeZone(timeZone)
        if (!athletes.lock(id)) throw NotFoundException("athlete $id not found")
        athletes.setTimeZone(id, zone.id)
        return zone.id to metrics.recomputeFrom(id, OffsetDateTime.of(1900, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
    }

    // FTP 이력 전체(없는 선수 404).
    fun thresholds(id: UUID): List<Threshold> {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        return athletes.thresholds(id)
    }

    // FTP 변경: 선수 행을 잠근 뒤(같은 선수의 업로드와 겹치지 않게) 이력을 넣거나 같은 날짜 값을 덮어쓰고, 그
    // 날짜 0시(선수 시간대의 현지 0시) 이후 시작한 활동의 지표를 다시 계산한다. 재계산은 활동 날짜마다 유효한 FTP를 다시
    // 찾으므로, 더 뒤 날짜의 FTP가 있는 활동은 그 값이 그대로 쓰인다. 다시 계산한 활동 수를 돌려준다.
    /** 어떤 날짜부터의 FTP를 정하고, 그 날짜 이후 활동의 TSS를 다시 계산한다. */
    @Transactional
    fun setThreshold(id: UUID, effectiveFrom: LocalDate, ftpWatts: Int): Int {
        requireReasonableDate(effectiveFrom, "effectiveFrom")
        if (!athletes.lock(id)) throw NotFoundException("athlete $id not found")
        athletes.setThreshold(id, effectiveFrom, ftpWatts, "manual")
        val zone = ZoneId.of(athletes.find(id)!!.timeZone)
        return metrics.recomputeFrom(id, effectiveFrom.atStartOfDay(zone).toOffsetDateTime())
    }

    // PMC 시계열. 범위는 to ≥ from이고 길이는 3 × 366일 이하, 날짜는 1900~2100년으로 제한한다. 어기면 400
    // bad_request(BadRequestException).
    fun trainingLoad(id: UUID, from: LocalDate, to: LocalDate, zone: ZoneId): List<TrainingLoad.Day> {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        requireReasonableDate(from, "from")
        requireReasonableDate(to, "to")
        if (to.isBefore(from)) throw BadRequestException("'to' must not be before 'from'")
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 3 * 366) throw BadRequestException("range must be at most three years")
        // 부하는 지수 가중 평균이라 조회 시작일 전의 훈련이 남아 있어야 한다. CTL이 조회 시작일에 좌우되지
        // 않도록 선수의 첫 활동부터 조회 전날까지 먼저 계산(워밍업)해 그 마지막 값을 초기값으로 넘긴다.
        // 처음에는 120일만 워밍업해 CTL의 약 6%가 빠졌고, 조회 시작일에 따라 CTL이 최대 6% 달라졌다
        // (2026-10-10 Codex 2차 검토 지적 4). 첫 활동의 현지 날짜도 Java가 센다(DB에서는 가장 이른 시작 시각만 읽는다).
        val firstDay = activities.firstStartedAt(id)?.atZoneSameInstant(zone)?.toLocalDate()
        val warmFrom = if (firstDay != null && firstDay.isBefore(from)) firstDay else from
        // 날짜별 TSS 합은 워밍업 시작일부터 to까지 한 번에 읽는다.
        val daily = dailyTss(id, warmFrom, to, zone)
        val warm = if (warmFrom.isBefore(from)) TrainingLoad.series(warmFrom, from.minusDays(1), daily).lastOrNull() else null
        return TrainingLoad.series(from, to, daily, warm?.ctl ?: 0.0, warm?.atl ?: 0.0)
    }

    /**
     * [from]~[to](양 끝 포함) 현지 날짜별 TSS 합(PMC 입력). DB에서는 그 날짜들에 해당하는 순간 범위 [from 현지 0시, to 다음
     * 날 현지 0시)에 시작한 활동의 (시작 시각, TSS)만 읽고, 날짜는 Java가 `atZoneSameInstant(zone).toLocalDate()`로 매겨
     * 더한다. 활동에 적용할 FTP를 고르는 날짜(MetricsService.compute)와 같은 식이라 한 활동의 FTP 날짜와 PMC 날짜가 어긋날 수
     * 없다. 현지 0시는 `LocalDate.atStartOfDay(zone)`로 구한다: 서머타임 전환으로 0시가 없는 날은 그날의 첫 유효 시각이 되고,
     * 23·25시간인 날도 그날에 속한 순간만 담긴다(TimeZoneTest의 Europe/Berlin 경계 테스트). 반열린 구간이라 다음 날 0시에
     * 시작한 활동은 다음 날로 가며, 기간 파워 커브·창 안 라이딩 수와 같은 경계 규칙이다. 같은 날 TSS는 시작 시각 순으로
     * Double로 더한다(2026-10-24 전에는 SQL의 sum(real)이 float4(유효 숫자 약 7자리)로 더해, 하루에 활동이 여럿이면 그
     * 정밀도만큼 합이 조금 달랐다).
     */
    private fun dailyTss(id: UUID, from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, Double> {
        val start = from.atStartOfDay(zone).toOffsetDateTime()
        val end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
        val byDay = HashMap<LocalDate, Double>()
        for ((startedAt, tss) in activities.tssByStart(id, start, end)) {
            byDay.merge(startedAt.atZoneSameInstant(zone).toLocalDate(), tss, Double::plus)
        }
        return byDay
    }

    // FTP 추정 근거인 '창 안 파워 라이딩 수'. 기간은 [asOf 다음 날 현지 0시 − weeks주, asOf 다음 날 현지 0시)라
    // asOf 당일을 포함한다(docs/experiments.md 규칙 변경 7).
    /** [asOf]까지(당일 포함) 최근 [weeks]주 동안의 파워 라이딩 수. */
    fun powerRideCount(id: UUID, weeks: Int, asOf: LocalDate, zone: ZoneId): Int {
        val to = asOf.plusDays(1).atStartOfDay(zone)
        return activities.countPowerActivities(id, to.minusWeeks(weeks.toLong()).toOffsetDateTime(), to.toOffsetDateTime())
    }

    // 기간 최대 파워 커브: 기간 안 활동들에 저장된 16구간 MMP의 구간별 최댓값. 기간은 반열린 구간 [from,
    // to)라 다음 날 0시는 포함하지 않는다(Codex 2차 검토 지적 7: 처음에는 between이 다음 날 자정을 포함했다).
    // weeks는 1~104주만 받는다.
    /** [asOf]까지(당일 포함) 최근 [weeks]주 동안 표준 구간마다의 최대 파워. */
    fun powerCurve(id: UUID, weeks: Int, asOf: LocalDate, zone: ZoneId): List<Pair<Int, Double>> {
        if (!athletes.exists(id)) throw NotFoundException("athlete $id not found")
        if (weeks !in 1..104) throw BadRequestException("weeks must be between 1 and 104")
        requireReasonableDate(asOf, "asOf")
        // 기간의 경계는 선수(또는 요청) 시간대의 현지 0시다. 시간대 규칙(서머타임 등)은 ZonedDateTime이 맞춘다.
        val to = asOf.plusDays(1).atStartOfDay(zone)
        val from = to.minusWeeks(weeks.toLong())
        val best = activities.maxMmp(id, from.toOffsetDateTime(), to.toOffsetDateTime())
        // 표준 구간 순서대로, 값이 있고 0보다 큰 것만 (초, W) 쌍으로 돌려준다. 3000 W 가드로 0이 된 값은
        // 여기서 빠진다.
        return MeanMaximalPower.STANDARD_WINDOWS_SECS.toList().mapNotNull { w ->
            best[MeanMaximalPower.metricKey(w)]?.takeIf { it > 0 }?.let { w to it }
        }
    }
}
