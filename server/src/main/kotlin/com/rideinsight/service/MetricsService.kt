package com.rideinsight.service

// [파일 역할] 활동 하나의 지표(NP, IF, TSS, 평균·최대 파워, 일, 16구간 MMP)를 저장된 샘플로 계산해
// activity_metrics에 쓰고, FTP가 바뀌면 영향받는 활동을 다시 계산하는 서비스다.
// [설계 이유] 계산 자체는 Spring에 의존하지 않는 metrics 패키지의 순수 함수가 하고, 이 서비스는 '그 활동
// 날짜에 유효한 FTP 찾기 → 순수 함수 호출 → 저장'만 맡는다. 그래서 업로드(ActivityService), FTP
// 변경(AthleteService), OpenData 가져오기(ImportOpenData)가 같은 계산 경로를 쓴다.
// [연결] 위 세 곳이 computeAndStore·recomputeFrom을 부르고, ActivityService가 rideSamples·parseMmp를 쓴다.
// JSON 변환에는 Spring이 만든 JsonMapper(Jackson 3) 빈을 주입받아 쓴다.

import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.PowerMetrics
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.metrics.RideSamples
import com.rideinsight.storage.ActivityRepository
import com.rideinsight.storage.AthleteRepository
import com.rideinsight.storage.MetricsRow
import com.rideinsight.storage.SampleSeries
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

/** Computes and stores per-activity metrics (NP, IF, TSS, MMP, averages) from stored samples. */
@Service
class MetricsService(
    private val athletes: AthleteRepository,
    private val activities: ActivityRepository,
    private val mapper: JsonMapper,
) {
    companion object {
        // 지표 코드가 바뀌면 올리는 버전. 저장된 행마다 함께 기록해 어떤 코드로 계산한 값인지 알 수 있게
        // 한다(docs/api.md: 지표 코드가 바뀌면 다시 계산해 덮어쓴다).
        /** Bump when the metric code changes so stored rows can be recomputed. */
        const val METRICS_VERSION = 1
    }

    // 저장 형식(SampleSeries, 결측은 null) → 지표 입력(RideSamples, 결측은 0 W)으로 바꾼다. 파워 시리즈
    // 자체가 없으면 모든 샘플을 결측으로 센다.
    fun rideSamples(series: SampleSeries): RideSamples {
        val power = series.power
        val missing = power?.count { it == null } ?: series.secs.size
        val watts = DoubleArray(series.secs.size) { i -> (power?.get(i) ?: 0).toDouble() }
        return RideSamples(series.secs.toDoubleArray(), watts, missing)
    }

    // 파워가 없는 활동은 계산하지 않고 null(지표 행 없음)을 돌려준다. 있으면 계산한 뒤 mmp를 JSON 문자열로
    // 바꿔 upsert한다(이미 있으면 덮어씀).
    /** Computes metrics for one activity and writes them; returns the row (null when no power). */
    fun computeAndStore(activityId: UUID, athleteId: UUID, startedAt: OffsetDateTime, series: SampleSeries, hasPower: Boolean): MetricsRow? {
        if (!hasPower) return null
        val row = compute(athleteId, startedAt, series)
        activities.saveMetrics(activityId, row, mapper.writeValueAsString(row.mmp))
        return row
    }

    // 계산 순서: 기록 간격 추정 → 활동 시작일(UTC)에 유효한 FTP 조회 → NP → IF·TSS(FTP가 없으면 null) →
    // 16구간 MMP(GC 3000 W 가드 적용) → 평균·최대 파워, 일. 기록 간격을 추정할 수 없으면(샘플 2개 미만 등)
    // 간격이 필요한 값은 모두 null이다.
    fun compute(athleteId: UUID, startedAt: OffsetDateTime, series: SampleSeries): MetricsRow {
        val samples = rideSamples(series)
        val interval = RecordingInterval.analyse(samples.secs)
        val recInt = interval?.intervalSecs
        // FTP는 활동 시작 시각의 UTC 날짜로 찾는다(effective_from ≤ 그 날짜 중 가장 최근 값).
        val ftp = athletes.thresholdOn(athleteId, startedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate())?.ftpWatts
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
        )
    }

    // from 이후 시작한 활동을 오래된 순으로 다시 계산한다. 파워가 없거나 샘플이 없는 활동은 건너뛰고, 다시
    // 계산한 수를 돌려준다. 호출하는 AthleteService.setThreshold의 트랜잭션(선수 행 잠금) 안에서 돈다.
    /** Recomputes every activity of the athlete that started on or after [from] (after an FTP change). */
    fun recomputeFrom(athleteId: UUID, from: OffsetDateTime): Int {
        var n = 0
        for (a in activities.listFrom(athleteId, from)) {
            if (!a.hasPower) continue
            val series = activities.samples(a.id) ?: continue
            computeAndStore(a.id, athleteId, a.startedAt, series, true)
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
}
