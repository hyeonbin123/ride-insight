package com.rideinsight.service

// [파일 역할] 활동(라이딩)의 수집과 조회를 맡는 서비스다. FIT 업로드를 디코딩·검사해 활동·샘플·지표를
// 저장하고(ingestFit), 상세·목록·시계열·활동 파워 커브·삭제를 처리한다.
// [설계 이유] 업로드 한 건은 '선수 행 잠금 → 디코딩 → 종목·시각 검사 → 활동·샘플 저장 → 지표 계산·저장'을 한
// 트랜잭션으로 묶어, 중간에 실패하면 아무것도 남지 않게 한다. 선수 행 잠금은 같은 선수의 FTP 변경과 업로드가
// 겹쳐 옛 FTP로 계산된 지표가 남는 것을 막는다(Codex 2차 검토 지적 2).
// [연결] AthleteController(업로드·목록)와 ActivityController(상세·샘플·커브·삭제)가 부른다. 저장은
// ActivityRepository·AthleteRepository, 지표 계산은 MetricsService가 한다.

import com.rideinsight.fit.FitActivity
import com.rideinsight.fit.FitProfile
import com.rideinsight.metrics.PowerCurve
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.storage.ActivityRepository
import com.rideinsight.storage.ActivityRow
import com.rideinsight.storage.AthleteRepository
import com.rideinsight.storage.MetricsRow
import com.rideinsight.storage.SampleSeries
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.abs
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 활동 행과 그 지표(파워가 없으면 null)를 묶은 서비스 결과. 컨트롤러가 ActivityResponse로 바꾼다.
class StoredActivity(val activity: ActivityRow, val metrics: MetricsRow?)

/**
 * 업로드 파일을 저장 직전까지 처리한 결과([ActivityService.prepare]): 디코딩한 활동, 시작 시각(UTC), 저장할
 * 시계열, 기록 간격 추정, 파워 유무와 결측 수.
 */
class PreparedActivity(
    val fit: FitActivity,
    val startedAt: OffsetDateTime,
    val series: SampleSeries,
    val interval: RecordingInterval.Estimate?,
    val hasPower: Boolean,
    val powerMissing: Int,
)

@Service
class ActivityService(
    private val athletes: AthleteRepository,
    private val activities: ActivityRepository,
    private val metrics: MetricsService,
) {
    companion object {
        // 거절할 종목 코드: 1 running, 5 swimming, 11 walking, 17 hiking. 그 밖의 종목과 종목 미상은
        // 저장한다(블랙리스트 방식, docs/api.md; Codex 2차 검토 지적 15로 문서를 이 정책에 맞춤).
        /** 거절하는 종목. 여기의 지표는 사이클링 파워에 대해서만 정의돼 있다. */
        val REJECTED_SPORTS: Map<Long, String> = mapOf(
            FitProfile.Sport.RUNNING to "running",
            5L to "swimming",
            11L to "walking",
            17L to "hiking",
        )
        // DB smallint 컬럼에 맞춘 파워 상한(W).
        const val MAX_POWER_STORED = 32_000 // smallint 컬럼이라 이보다 큰 값은 결측으로 다룬다

        // DB real(float4) 열이 담을 수 있는 가장 큰 절댓값.
        private const val REAL_MAX = Float.MAX_VALUE.toDouble()

        // 목록 보기 위에 조건 보기를 하나 더 씌운다(복사 없음): 조건을 어기는 원소는 null로 보인다. 남는 값이 하나도
        // 없으면 null을 돌려준다(시리즈 없음으로 저장).
        private fun <T : Any> List<T?>.keepIf(keep: (T) -> Boolean): List<T?>? {
            val base = this
            if (base.none { it != null && keep(it) }) return null
            return object : AbstractList<T?>() {
                override val size: Int get() = base.size
                override fun get(index: Int): T? = base[index]?.takeIf(keep)
            }
        }

        /**
         * 업로드 바이트 → 저장 직전의 활동. DB도 Spring도 쓰지 않는 순수 함수라 퍼징 테스트(FitFuzzTest)가 수천
         * 건을 그대로 넣어 예외 계약을 확인한다. 예외: 디코딩 실패 FitDecodeException(400), 처리 상한
         * FitLimitException(413), 거절 종목 UnsupportedSportException(422), record 없음·시각 역행
         * InvalidActivityException(400). 그 밖의 예외는 버그다.
         */
        fun prepare(bytes: ByteArray): PreparedActivity {
            val fit = FitActivity.decode(bytes) // 디코딩 실패(FitDecodeException)는 400
            REJECTED_SPORTS[fit.sport]?.let { throw UnsupportedSportException("sport '$it' is not supported; upload a cycling activity") }
            if (fit.records.isEmpty()) throw InvalidActivityException("the file has no record messages")

            // 첫 record 시각을 0초로 두고 경과 시간(초)으로 바꾼다. 활동 시작 시각도 이 첫 record 시각이다.
            // record는 열(FitRecords)로 담겨 있어, 시계열도 원시 배열과 그 위의 목록 보기로 만들어 원소마다
            // 객체를 만들지 않는다(2026-10-15, docs/experiments.md 5단계 '업로드 자원 증폭').
            val records = fit.records
            val first = records.timestampUnix[0]
            val secsArray = DoubleArray(records.size) { (records.timestampUnix[it] - first).toDouble() }
            // 시각이 거꾸로 가는 파일은 거절한다(400 invalid_activity, 변조 파일 (6)). 같은 시각이 반복되는 것은
            // 허용한다.
            for (i in 1 until secsArray.size) {
                if (secsArray[i] < secsArray[i - 1]) throw InvalidActivityException("record timestamps go backwards at sample $i")
            }
            // 0~32,000 W 밖의 파워는 결측(null)으로 본다. 유효한 파워가 하나도 없으면 파워 없는 활동으로 저장하고
            // 지표는 계산하지 않는다(api.md: 파워 없는 파일은 저장하되 지표 null).
            val rawPower = records.powerColumn()
            val power: List<Int?> = object : AbstractList<Int?>() {
                override val size: Int get() = secsArray.size
                override fun get(index: Int): Int? = rawPower?.get(index)?.takeIf { it in 0..MAX_POWER_STORED }
            }
            val hasPower = power.any { it != null }
            // 저장할 시리즈. 값이 하나도 없는 시리즈는 null(센서 없음)로, 일부만 빠진 시리즈는 원소 null로
            // 저장한다.
            val series = SampleSeries(
                secs = secsArray.asList(),
                power = if (hasPower) power else null,
                // 심박·케이던스는 smallint 열이라 파워와 같은 0~32,000 범위만 담고, 거리·고도는 real(float4) 열에
                // 들어가는 유한한 값만 담는다. 정의가 필드 타입을 uint32·float64 등으로 바꾼 파일에서 범위 밖 값이
                // 그대로 저장되려다 PostgreSQL 범위 오류(500)가 나는 것을 2026-10-15 퍼징(FitFuzzTest)이 잡았다.
                // 범위 밖 값은 결측(null)으로 보고, 남는 값이 없으면 시리즈 자체를 null(센서 없음)로 둔다.
                heartRate = records.heartRateColumn()?.keepIf { it in 0..MAX_POWER_STORED },
                cadence = records.cadenceColumn()?.keepIf { it in 0..MAX_POWER_STORED },
                distanceM = records.distanceColumn()?.keepIf { it.isFinite() && abs(it) <= REAL_MAX },
                altitudeM = records.altitudeColumn()?.keepIf { it.isFinite() && abs(it) <= REAL_MAX },
            )
            return PreparedActivity(
                fit = fit,
                // 시작 시각은 UTC로 저장한다. 같은 선수의 같은 시작 시각은 DB unique 제약에 걸려 409(중복
                // 업로드)가 된다.
                startedAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(first), ZoneOffset.UTC),
                series = series,
                // 기록 간격과 그 규칙성(최빈 간격 비율)을 추정해 활동에 함께 저장한다.
                interval = RecordingInterval.analyse(secsArray),
                hasPower = hasPower,
                powerMissing = power.count { it == null },
            )
        }
    }

    // FIT 업로드 처리. 입력: 선수 id, 파일 바이트, 원래 파일 이름. 출력: 저장된 활동과 지표. 예외: 없는 선수
    // 404, 디코딩 실패 400, 처리 상한 초과 413, 거절 종목 422, record 없음·시각 역행 400, 같은 시작 시각 409.
    // 예외가 나면 @Transactional이 그때까지 쓴 것을 모두 롤백한다. DB가 필요 없는 앞부분(디코딩부터 저장할 시계열
    // 만들기까지)은 [prepare]에 있다.
    @Transactional
    fun ingestFit(athleteId: UUID, bytes: ByteArray, fileName: String?): StoredActivity {
        // 선수 행을 select ... for update로 잠가 같은 선수의 업로드·FTP 변경을 한 번에 하나씩 처리한다.
        // 선수가 없으면 404.
        if (!athletes.lock(athleteId)) throw NotFoundException("athlete $athleteId not found")
        val p = prepare(bytes)
        val id = activities.insert(
            ActivityRepository.NewActivity(
                athleteId = athleteId,
                startedAt = p.startedAt,
                source = "fit",
                sourceName = fileName?.take(200),
                sport = FitProfile.sportName(p.fit.sport),
                recIntSecs = p.interval?.intervalSecs,
                intervalShare = p.interval?.modeShare,
                sampleCount = p.series.secs.size,
                // 길이 = 마지막 경과 시간 + 기록 간격(마지막 샘플이 대표하는 시간까지 포함, MMP의 라이딩 길이
                // 정의와 같다).
                durationSecs = p.series.secs.last() + (p.interval?.intervalSecs ?: 0.0),
                manufacturer = p.fit.fileId?.manufacturer?.toInt(),
                product = p.fit.fileId?.product?.toInt(),
                crcValid = p.fit.crcValid,
                hasPower = p.hasPower,
                powerMissing = p.powerMissing,
            ),
            p.series,
        )
        // 같은 트랜잭션에서 지표를 계산해 저장한다. FTP는 활동 날짜에 유효한 값을 쓴다. 응답에는 DB가 채운
        // id·created_at이 필요해 방금 넣은 활동 행을 다시 읽는다.
        val row = metrics.computeAndStore(id, athleteId, p.startedAt, p.series, p.hasPower)
        return StoredActivity(activities.find(id)!!, row)
    }

    // 활동 상세(없으면 404).
    fun get(id: UUID): StoredActivity {
        val a = activities.find(id) ?: throw NotFoundException("activity $id not found")
        return StoredActivity(a, loadMetrics(id))
    }

    // 선수의 활동 목록(최신순, before 커서, limit 1~200). 지표는 activity_id = any(배열) 쿼리 한 번으로 모아
    // 읽어, 활동마다 쿼리를 따로 보내지 않는다.
    fun list(athleteId: UUID, before: OffsetDateTime?, limit: Int): List<StoredActivity> {
        if (!athletes.exists(athleteId)) throw NotFoundException("athlete $athleteId not found")
        before?.let { requireReasonableDate(it.toLocalDate(), "before") }
        val rows = activities.list(athleteId, before, limit.coerceIn(1, 200))
        val all = activities.metricsFor(rows.map { it.id })
        return rows.map { r -> StoredActivity(r, all[r.id]?.let { (m, json) -> withMmp(m, json) }) }
    }

    // 저장된 시계열. 활동이 없거나 샘플 행이 없으면 404.
    fun samples(id: UUID): SampleSeries {
        if (activities.find(id) == null) throw NotFoundException("activity $id not found")
        return activities.samples(id) ?: throw NotFoundException("activity $id has no samples")
    }

    // 활동 전 구간 파워 커브(3000 W 가드 없는 best, 로그 간격 300점 이하)와 저장된 16구간 값. 곡선은 업로드 때
    // 계산해 저장한 것을 읽는다(2026-10-15 전에는 요청마다 샘플 전체를 읽어 다시 계산했다, metrics.PowerCurve
    // 주석). 곡선을 저장하기 전에 올린 활동만 샘플로 계산한다. 파워가 없으면 빈 곡선이다.
    /** 활동 하나의 전 구간 최대 평균 파워 곡선. 지속 시간은 로그 간격 격자다(최대 약 300점). */
    fun powerCurve(id: UUID): Pair<List<Pair<Int, Double>>, Map<String, Double?>> {
        val a = activities.find(id) ?: throw NotFoundException("activity $id not found")
        val stored = loadMetrics(id)?.mmp ?: emptyMap()
        if (!a.hasPower) return emptyList<Pair<Int, Double>>() to stored
        activities.curveJson(id)?.let { return metrics.parseCurve(it) to stored }
        val series = activities.samples(id) ?: throw NotFoundException("activity $id has no samples")
        return PowerCurve.points(MetricsService.rideSamples(series)) to stored
    }

    // 삭제. 샘플·지표는 DB cascade로 함께 지워진다. 없으면 404.
    fun delete(id: UUID) {
        if (!activities.delete(id)) throw NotFoundException("activity $id not found")
    }

    // 지표 행과 mmp JSON 텍스트를 읽어 MetricsRow 하나로 합친다. 저장소는 JSON을 해석하지 않으므로(mmp = 빈
    // 맵) 여기서 MetricsService.parseMmp로 채운다.
    private fun loadMetrics(id: UUID): MetricsRow? = activities.metrics(id)?.let { (m, json) -> withMmp(m, json) }

    private fun withMmp(m: MetricsRow, json: String) = MetricsRow(
        m.metricsVersion, m.ftpUsed, m.normalizedPower, m.intensityFactor, m.tss, m.averagePower, m.maxPower, m.workKj,
        metrics.parseMmp(json),
    )
}
