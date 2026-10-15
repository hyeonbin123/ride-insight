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
import com.rideinsight.metrics.MeanMaximalPower
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 활동 행과 그 지표(파워가 없으면 null)를 묶은 서비스 결과. 컨트롤러가 ActivityResponse로 바꾼다.
class StoredActivity(val activity: ActivityRow, val metrics: MetricsRow?)

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
        // 활동 파워 커브의 최대 점 수(docs/api.md: 곡선은 로그 간격으로 300점 이하).
        const val MAX_CURVE_POINTS = 300
    }

    // FIT 업로드 처리. 입력: 선수 id, 파일 바이트, 원래 파일 이름. 출력: 저장된 활동과 지표. 예외: 없는 선수
    // 404, 디코딩 실패 400, 거절 종목 422, record 없음·시각 역행 400, 같은 시작 시각 409. 예외가 나면
    // @Transactional이 그때까지 쓴 것을 모두 롤백한다.
    @Transactional
    fun ingestFit(athleteId: UUID, bytes: ByteArray, fileName: String?): StoredActivity {
        // 선수 행을 select ... for update로 잠가 같은 선수의 업로드·FTP 변경을 한 번에 하나씩 처리한다.
        // 선수가 없으면 404.
        if (!athletes.lock(athleteId)) throw NotFoundException("athlete $athleteId not found")
        val fit = FitActivity.decode(bytes) // 디코딩 실패(FitDecodeException)는 400
        val sport = fit.sport
        REJECTED_SPORTS[sport]?.let { throw UnsupportedSportException("sport '$it' is not supported; upload a cycling activity") }
        if (fit.records.isEmpty()) throw InvalidActivityException("the file has no record messages")

        // 첫 record 시각을 0초로 두고 경과 시간(초)으로 바꾼다. 활동 시작 시각도 이 첫 record 시각이다. record는
        // 열(FitRecords)로 담겨 있어, 시계열도 원시 배열과 그 위의 목록 보기로 만들어 원소마다 객체를 만들지
        // 않는다(2026-10-15, docs/experiments.md 5단계 '업로드 자원 증폭').
        val records = fit.records
        val first = records.timestampUnix[0]
        val secsArray = DoubleArray(records.size) { (records.timestampUnix[it] - first).toDouble() }
        // 시각이 거꾸로 가는 파일은 거절한다(400 invalid_activity, 변조 파일 (6)). 같은 시각이 반복되는 것은
        // 허용한다.
        for (i in 1 until secsArray.size) {
            if (secsArray[i] < secsArray[i - 1]) throw InvalidActivityException("record timestamps go backwards at sample $i")
        }
        val secs = secsArray.asList()
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
            secs = secs,
            power = if (hasPower) power else null,
            heartRate = records.heartRateColumn(),
            cadence = records.cadenceColumn(),
            distanceM = records.distanceColumn(),
            altitudeM = records.altitudeColumn(),
        )
        // 기록 간격과 그 규칙성(최빈 간격 비율)을 추정해 활동에 함께 저장한다.
        val interval = RecordingInterval.analyse(secsArray)
        // 시작 시각은 UTC로 저장한다. 같은 선수의 같은 시작 시각은 DB unique 제약에 걸려 409(중복 업로드)가
        // 된다.
        val startedAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(first), ZoneOffset.UTC)
        val id = activities.insert(
            ActivityRepository.NewActivity(
                athleteId = athleteId,
                startedAt = startedAt,
                source = "fit",
                sourceName = fileName?.take(200),
                sport = FitProfile.sportName(sport),
                recIntSecs = interval?.intervalSecs,
                intervalShare = interval?.modeShare,
                sampleCount = secs.size,
                // 길이 = 마지막 경과 시간 + 기록 간격(마지막 샘플이 대표하는 시간까지 포함, MMP의 라이딩 길이
                // 정의와 같다).
                durationSecs = secs.last() + (interval?.intervalSecs ?: 0.0),
                manufacturer = fit.fileId?.manufacturer?.toInt(),
                product = fit.fileId?.product?.toInt(),
                crcValid = fit.crcValid,
                hasPower = hasPower,
                powerMissing = power.count { it == null },
            ),
            series,
        )
        // 같은 트랜잭션에서 지표를 계산해 저장한다. FTP는 활동 날짜에 유효한 값을 쓴다. 응답에는 DB가 채운
        // id·created_at이 필요해 방금 넣은 활동 행을 다시 읽는다.
        val row = metrics.computeAndStore(id, athleteId, startedAt, series, hasPower)
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
        val rows = activities.list(athleteId, before, limit.coerceIn(1, 200))
        val all = activities.metricsFor(rows.map { it.id })
        return rows.map { r -> StoredActivity(r, all[r.id]?.let { (m, json) -> withMmp(m, json) }) }
    }

    // 저장된 시계열. 활동이 없거나 샘플 행이 없으면 404.
    fun samples(id: UUID): SampleSeries {
        if (activities.find(id) == null) throw NotFoundException("activity $id not found")
        return activities.samples(id) ?: throw NotFoundException("activity $id has no samples")
    }

    // 활동 전 구간 파워 커브: 저장된 샘플로 로그 간격 지속 시간마다 MMP를 다시 계산한다(3000 W 가드 없는
    // best). 파워가 없거나 기록 간격을 추정할 수 없으면 빈 곡선과 저장된 16구간 값만 돌려준다.
    /** 활동 하나의 전 구간 최대 평균 파워 곡선. 지속 시간은 로그 간격 격자다(최대 약 300점). */
    fun powerCurve(id: UUID): Pair<List<Pair<Int, Double>>, Map<String, Double?>> {
        val a = activities.find(id) ?: throw NotFoundException("activity $id not found")
        val series = activities.samples(id) ?: throw NotFoundException("activity $id has no samples")
        val stored = loadMetrics(id)?.mmp ?: emptyMap()
        if (!a.hasPower) return emptyList<Pair<Int, Double>>() to stored
        val rs = metrics.rideSamples(series)
        val recInt = RecordingInterval.estimate(rs.secs) ?: return emptyList<Pair<Int, Double>>() to stored
        // 곡선의 최대 지속 시간 = 라이딩 길이(마지막 secs + 기록 간격). 라이딩보다 긴 구간은 best가 null이라
        // 건너뛴다.
        val maxSecs = (rs.secs.last() + recInt).toInt()
        val points = ArrayList<Pair<Int, Double>>()
        for (d in curveDurations(maxSecs)) {
            val w = MeanMaximalPower.best(rs, recInt, d.toDouble()) ?: continue
            points.add(d to w)
        }
        return points to stored
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

    // 1초부터 시작해 다음 값 = max(d + 1, d × growth)로 늘리는 로그 간격 격자에, 16개 표준 구간과 최대 길이를
    // 더한다(정렬된 집합이라 중복은 한 번만). 점이 300개를 넘으면 growth를 1.5배씩 키워 다시 만든다. 처음에는
    // 300점을 넘을 수 있었다(Codex 2차 검토 지적 11).
    /** [maxSecs]까지의 로그 간격 지속 시간 목록. 개수는 [MAX_CURVE_POINTS]를 넘지 않는다. */
    internal fun curveDurations(maxSecs: Int): List<Int> {
        var growth = 1.04
        while (true) {
            val out = sortedSetOf<Int>()
            var d = 1.0
            while (d <= maxSecs) {
                out.add(d.toInt())
                d = maxOf(d + 1, d * growth)
            }
            for (w in MeanMaximalPower.STANDARD_WINDOWS_SECS) if (w <= maxSecs) out.add(w)
            if (maxSecs >= 1) out.add(maxSecs)
            if (out.size <= MAX_CURVE_POINTS) return out.toList()
            growth *= 1.5 // 비정상 라이딩(예: 일주일 떨어진 샘플 두 개)은 더 성긴 격자가 필요하다
        }
    }
}
