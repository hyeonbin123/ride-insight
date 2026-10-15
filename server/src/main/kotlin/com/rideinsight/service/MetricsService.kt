package com.rideinsight.service

// [파일 역할] 활동 하나의 지표(NP, IF, TSS, 평균·최대 파워, 일, 16구간 MMP)를 저장된 샘플로 계산해
// activity_metrics에 쓰고, FTP가 바뀌면 영향받는 활동을 다시 계산하는 서비스다.
// [설계 이유] 계산 자체는 Spring에 의존하지 않는 metrics 패키지의 순수 함수가 하고, 이 서비스는 '그 활동
// 날짜에 유효한 FTP 찾기 → 순수 함수 호출 → 저장'만 맡는다. 그래서 업로드(ActivityService), FTP
// 변경(AthleteService), OpenData 가져오기(ImportOpenData)가 같은 계산 경로를 쓴다.
// [연결] 위 세 곳이 computeAndStore·recomputeFrom을 부르고, ActivityService가 rideSamples·parseMmp를 쓴다.
// JSON 변환에는 Spring이 만든 JsonMapper(Jackson 3) 빈을 주입받아 쓴다.

import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.PowerCurve
import com.rideinsight.metrics.PowerMetrics
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.metrics.RideSamples
import com.rideinsight.storage.ActivityRepository
import com.rideinsight.storage.AthleteRepository
import com.rideinsight.storage.MetricsRow
import com.rideinsight.storage.SampleSeries
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

/** 저장된 샘플로 활동별 지표(NP, IF, TSS, MMP, 평균값)를 계산해 저장한다. */
@Service
class MetricsService(
    private val athletes: AthleteRepository,
    private val activities: ActivityRepository,
    private val mapper: JsonMapper,
) {
    companion object {
        /**
         * 지표 코드가 바뀌면 올리는 버전. 저장된 행마다 함께 기록해 어떤 코드로 계산한 값인지 알 수 있게 하고,
         * 저장된 행을 다시 계산할 수 있게 한다(docs/api.md: 지표 코드가 바뀌면 다시 계산해 덮어쓴다).
         */
        const val METRICS_VERSION = 1

        // 저장 형식(SampleSeries, 결측은 null) → 지표 입력(RideSamples, 결측은 0 W)으로 바꾼다. 파워 시리즈
        // 자체가 없으면 모든 샘플을 결측으로 센다. DB도 Spring도 쓰지 않는 순수 함수라 퍼징 테스트도 부른다.
        fun rideSamples(series: SampleSeries): RideSamples {
            val power = series.power
            val missing = power?.count { it == null } ?: series.secs.size
            val watts = DoubleArray(series.secs.size) { i -> (power?.get(i) ?: 0).toDouble() }
            return RideSamples(series.secs.toDoubleArray(), watts, missing)
        }

        /**
         * 주어진 FTP로 지표를 계산하는 순수 함수(DB 없음). 계산 순서: 기록 간격 추정 → NP → IF·TSS(FTP가 없으면
         * null) → 16구간 MMP(GC 3000 W 가드 적용) → 평균·최대 파워, 일 → 전 구간 파워 커브. 기록 간격을 추정할
         * 수 없으면(샘플 2개 미만 등) 간격이 필요한 값은 모두 null이다.
         */
        fun computeWithFtp(series: SampleSeries, ftp: Int?): MetricsRow {
            val samples = rideSamples(series)
            val interval = RecordingInterval.analyse(samples.secs)
            val recInt = interval?.intervalSecs
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
            // 평균 파워는 결측을 0 W로 채운 지표 입력의 평균이고, 최대 파워는 실제로 기록된 값 중 최댓값이다.
            // 일(kJ) = 파워 합 × 기록 간격 / 1000.
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
                // 전 구간 파워 커브도 여기서 한 번 계산해 저장한다(2026-10-15: 조회마다 다시 계산하던 것을 옮김).
                curve = PowerCurve.points(samples),
            )
        }
    }

    // 파워가 없는 활동은 계산하지 않고 null(지표 행 없음)을 돌려준다. 있으면 계산한 뒤 mmp를 JSON 문자열로
    // 바꿔 upsert한다(이미 있으면 덮어씀).
    /** 활동 하나의 지표를 계산해 저장하고 그 행을 돌려준다(파워가 없으면 null). */
    fun computeAndStore(
        activityId: UUID,
        athleteId: UUID,
        startedAt: OffsetDateTime,
        series: SampleSeries,
        hasPower: Boolean,
        zone: ZoneId? = null,
    ): MetricsRow? {
        if (!hasPower) return null
        val row = compute(athleteId, startedAt, series, zone)
        // 곡선은 [[초, W], ...] 형식의 JSON으로 저장한다.
        val curveJson = row.curve?.let { c -> mapper.writeValueAsString(c.map { (s, w) -> listOf(s, w) }) }
        activities.saveMetrics(activityId, row, mapper.writeValueAsString(row.mmp), curveJson)
        return row
    }

    // 활동 시작 시각의 현지 날짜(선수 시간대)에 유효한 FTP를 찾아 [computeWithFtp]로 계산한다. FTP는
    // effective_from ≤ 그 날짜 중 가장 최근 값이다. 2026-10-15 전에는 UTC 날짜를 써서, 한국 시각 오전 9시 전에 시작한
    // 라이딩은 그날부터 바꾼 FTP가 아니라 전날 FTP로 계산될 수 있었다. [zone]을 주지 않으면 선수 행에서 읽는다.
    fun compute(athleteId: UUID, startedAt: OffsetDateTime, series: SampleSeries, zone: ZoneId? = null): MetricsRow {
        val z = zone ?: zoneOf(athleteId)
        val ftp = athletes.thresholdOn(athleteId, startedAt.atZoneSameInstant(z).toLocalDate())?.ftpWatts
        return computeWithFtp(series, ftp)
    }

    // 선수의 시간대. 선수가 없으면(호출 전에 확인하므로 실제로는 없음) 기본 시간대.
    private fun zoneOf(athleteId: UUID): ZoneId = ZoneId.of(athletes.find(athleteId)?.timeZone ?: DEFAULT_TIME_ZONE)

    // from 이후 시작한 활동을 오래된 순으로 다시 계산한다. 파워가 없거나 샘플이 없는 활동은 건너뛰고, 다시
    // 계산한 수를 돌려준다. 호출하는 AthleteService.setThreshold의 트랜잭션(선수 행 잠금) 안에서 돈다.
    /** [from] 이후에 시작한 선수의 모든 활동을 다시 계산한다(FTP 변경 뒤). */
    fun recomputeFrom(athleteId: UUID, from: OffsetDateTime): Int {
        var n = 0
        val zone = zoneOf(athleteId) // 활동마다 다시 읽지 않도록 한 번만 읽는다
        for (a in activities.listFrom(athleteId, from)) {
            if (!a.hasPower) continue
            val series = activities.samples(a.id) ?: continue
            computeAndStore(a.id, athleteId, a.startedAt, series, true, zone)
            n++
        }
        return n
    }

    // DB의 mmp jsonb 텍스트 → 구간 키별 Double?(null은 그 구간 값 없음). Jackson이 숫자를 Integer·Double
    // 등으로 읽을 수 있어 Number로 받아 Double로 바꾼다.
    fun parseMmp(json: String): Map<String, Double?> {
        @Suppress("UNCHECKED_CAST")
        val raw = mapper.readValue(json, Map::class.java) as Map<String, Any?>
        return raw.mapValues { (_, v) -> (v as Number?)?.toDouble() }
    }

    // DB의 curve jsonb 텍스트([[초, W], ...]) → (초, W) 목록.
    fun parseCurve(json: String): List<Pair<Int, Double>> {
        @Suppress("UNCHECKED_CAST")
        val raw = mapper.readValue(json, List::class.java) as List<List<Number>>
        return raw.map { (s, w) -> s.toInt() to w.toDouble() }
    }
}
