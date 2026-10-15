package com.rideinsight.storage

// [파일 역할] activity·activity_samples·activity_metrics 테이블을 읽고 쓰는 저장소다. SQL은 Spring의
// JdbcClient로 직접 쓴다.
// [설계 이유] JPA 대신 JdbcClient를 쓴 것은 초 단위 시계열을 활동당 한 행의 배열 컬럼(real[], smallint[])에
// 넣기 위해서다(docs/api.md 2단계: ORM이 방해가 된다). 배열은 PgArrays가 만든 리터럴 문자열로 바인딩하고 SQL
// 안에서 ::real[] 등으로 형 변환한다. 모든 값은 ? 자리표시자로 바인딩해 사용자 입력을 SQL 문자열에 이어
// 붙이지 않는다(Codex 2차 검토가 SQL 주입 경로 없음을 확인).
// [연결] ActivityService, AthleteService, MetricsService, tools/ImportOpenData가 쓴다. 스키마는
// src/main/resources/db/migration/V1__init.sql.

import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

// activity 테이블 한 행. 각 컬럼의 뜻은 api/Dtos.kt의 ActivityResponse 주석과 같다.
class ActivityRow(
    val id: UUID,
    val athleteId: UUID,
    val startedAt: OffsetDateTime,
    val source: String,
    val sourceName: String?,
    val sport: String?,
    val recIntSecs: Double?,
    val intervalShare: Double?,
    val sampleCount: Int,
    val durationSecs: Double?,
    val manufacturer: Int?,
    val product: Int?,
    val crcValid: Boolean?,
    val hasPower: Boolean,
    val powerMissing: Int,
    val createdAt: OffsetDateTime,
)

// activity_samples 한 행(활동당 하나): 경과 시간(초)과 시리즈들. 시리즈가 통째로 없으면 null, 일부 샘플만
// 빠지면 원소 null.
class SampleSeries(
    val secs: List<Double>,
    val power: List<Int?>?,
    val heartRate: List<Int?>?,
    val cadence: List<Int?>?,
    val distanceM: List<Double?>?,
    val altitudeM: List<Double?>?,
)

// activity_metrics 한 행. mmp는 DB에 jsonb로 저장되고, 읽을 때는 서비스가 JSON을 해석해 채운다.
class MetricsRow(
    val metricsVersion: Int,
    val ftpUsed: Int?,
    val normalizedPower: Double?,
    val intensityFactor: Double?,
    val tss: Double?,
    val averagePower: Double?,
    val maxPower: Int?,
    val workKj: Double?,
    val mmp: Map<String, Double?>,
    // 전 구간 파워 커브((초, W) 목록). 저장할 때만 채우고, 읽을 때는 서비스가 curve JSON을 따로 읽는다.
    val curve: List<Pair<Int, Double>>? = null,
)

// 같은 선수·같은 시작 시각의 활동이 이미 있을 때 던진다. API에서는 409, ImportOpenData에서는 건너뛴 수로
// 센다.
class DuplicateActivityException(message: String) : RuntimeException(message)

// Spring 빈(@Repository). JdbcClient는 Spring Boot가 DataSource로 만들어 주입한다.
@Repository
class ActivityRepository(private val jdbc: JdbcClient) {
    // insert 입력(id·created_at은 DB가 채운다).
    class NewActivity(
        val athleteId: UUID,
        val startedAt: OffsetDateTime,
        val source: String,
        val sourceName: String?,
        val sport: String?,
        val recIntSecs: Double?,
        val intervalShare: Double?,
        val sampleCount: Int,
        val durationSecs: Double?,
        val manufacturer: Int?,
        val product: Int?,
        val crcValid: Boolean?,
        val hasPower: Boolean,
        val powerMissing: Int,
    )

    // 활동 행과 샘플 행을 넣고 새 id를 돌려준다. 호출하는 쪽의 트랜잭션 안에서 돌기 때문에 둘 중 하나가
    // 실패하면 함께 롤백된다.
    fun insert(a: NewActivity, samples: SampleSeries): UUID {
        // returning id로 DB가 만든 uuid를 받는다. JdbcClient(내부의 JdbcTemplate)가 unique(athlete_id,
        // started_at) 위반을 Spring의 DuplicateKeyException으로 바꿔 주므로, 이를 도메인 예외로 바꿔 409가
        // 되게 한다. params(listOf(...))를 쓴 것은 Kotlin에서 vararg params()에 null 인자를 넘길 수 없었기
        // 때문이다(docs/ai-verification.md 2단계).
        val id = try {
            jdbc.sql(
                """
                insert into activity (athlete_id, started_at, source, source_name, sport, rec_int_secs, interval_share,
                    sample_count, duration_secs, manufacturer, product, crc_valid, has_power, power_missing)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """,
            ).params(
                listOf(
                    a.athleteId, a.startedAt, a.source, a.sourceName, a.sport, a.recIntSecs, a.intervalShare,
                    a.sampleCount, a.durationSecs, a.manufacturer, a.product, a.crcValid, a.hasPower, a.powerMissing,
                ),
            ).query(UUID::class.java).single()
        } catch (e: DuplicateKeyException) {
            throw DuplicateActivityException("an activity of this athlete already starts at ${a.startedAt}")
        }
        // 샘플은 배열 리터럴 문자열('{1,2,NULL}')로 넘기고 SQL에서 ::real[]·::smallint[]로 형 변환한다.
        // 드라이버별 배열 바인딩 지원에 기대지 않고 NULL 원소도 그대로 지킨다(PgArrays). 시리즈가 null이면
        // 컬럼 자체가 NULL이 된다.
        jdbc.sql(
            """
            insert into activity_samples (activity_id, secs, power, heart_rate, cadence, distance_m, altitude_m)
            values (?, ?::real[], ?::smallint[], ?::smallint[], ?::smallint[], ?::real[], ?::real[])
            """,
        ).params(
            listOf(
                id,
                PgArrays.literal(samples.secs),
                samples.power?.let { PgArrays.literal(it) },
                samples.heartRate?.let { PgArrays.literal(it) },
                samples.cadence?.let { PgArrays.literal(it) },
                samples.distanceM?.let { PgArrays.literal(it) },
                samples.altitudeM?.let { PgArrays.literal(it) },
            ),
        ).update()
        return id
    }

    // 지표 upsert: 처음이면 insert, 이미 있으면(FTP 변경 후 재계산) 모든 값과 computed_at을 덮어쓴다. mmp와
    // curve(전 구간 파워 커브, V2__activity_metrics_curve.sql)는 JSON 문자열을 ::jsonb로 형 변환해 넣는다.
    fun saveMetrics(activityId: UUID, m: MetricsRow, mmpJson: String, curveJson: String?) {
        jdbc.sql(
            """
            insert into activity_metrics (activity_id, metrics_version, ftp_used, normalized_power, intensity_factor, tss,
                average_power, max_power, work_kj, mmp, curve)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            on conflict (activity_id) do update set metrics_version = excluded.metrics_version, ftp_used = excluded.ftp_used,
                normalized_power = excluded.normalized_power, intensity_factor = excluded.intensity_factor, tss = excluded.tss,
                average_power = excluded.average_power, max_power = excluded.max_power, work_kj = excluded.work_kj,
                mmp = excluded.mmp, curve = excluded.curve, computed_at = now()
            """,
        ).params(
            listOf(
                activityId, m.metricsVersion, m.ftpUsed, m.normalizedPower, m.intensityFactor, m.tss,
                m.averagePower, m.maxPower, m.workKj, mmpJson, curveJson,
            ),
        ).update()
    }

    /** 저장된 전 구간 파워 커브 JSON. 지표 행이 없거나 곡선을 저장하기 전(2026-10-15 전)의 행이면 null. */
    fun curveJson(activityId: UUID): String? =
        jdbc.sql("select curve::text from activity_metrics where activity_id = ?").param(activityId)
            .query(String::class.java).optional().orElse(null)

    // 활동 한 건(없으면 null).
    fun find(id: UUID): ActivityRow? =
        jdbc.sql("select * from activity where id = ?").param(id).query(::mapActivity).optional().orElse(null)

    // 선수의 활동을 최신순으로 limit개. 'before가 null이거나 started_at < before' 조건이라 쿼리 하나로 첫
    // 페이지와 다음 페이지를 모두 처리한다. ?::timestamptz로 형을 밝혀 두어야 PostgreSQL이 null 파라미터의
    // 형을 정할 수 있다. 이 정렬에 맞춘 (athlete_id, started_at desc) 인덱스가 V1__init.sql에 있다.
    fun list(athleteId: UUID, before: OffsetDateTime?, limit: Int): List<ActivityRow> =
        jdbc.sql(
            """
            select * from activity where athlete_id = ? and (?::timestamptz is null or started_at < ?::timestamptz)
            order by started_at desc limit ?
            """,
        ).params(listOf(athleteId, before, before, limit)).query(::mapActivity).list()

    /** 시작 시각이 [from] 이후(같은 시각 포함)인 활동을 오래된 순으로 돌려준다. FTP 변경 뒤 TSS 재계산 대상이다. */
    fun listFrom(athleteId: UUID, from: OffsetDateTime): List<ActivityRow> =
        jdbc.sql("select * from activity where athlete_id = ? and started_at >= ? order by started_at")
            .params(athleteId, from).query(::mapActivity).list()

    // 샘플 행을 읽어 SampleSeries로. 배열은 텍스트('{1,2,NULL}')로 받아 원시 배열로 직접 풀고, SampleSeries는 그
    // 배열 위의 목록 보기로 만든다. 처음에는 드라이버의 java.sql.Array로 읽어 원소마다 Float·Short 객체가 생기고
    // 다시 Double·Int 목록으로 복사해, 샘플 50만 개짜리 활동 하나를 읽는 데 100 MB 넘게 들었다(2026-10-15 측정,
    // docs/experiments.md 5단계 '업로드 자원 증폭'). secs는 NOT NULL 컬럼이고, 원소 NULL은 0.0으로 둔다(전과 같음).
    fun samples(activityId: UUID): SampleSeries? =
        jdbc.sql(
            """
            select secs::text as secs, power::text as power, heart_rate::text as heart_rate, cadence::text as cadence,
                distance_m::text as distance_m, altitude_m::text as altitude_m
            from activity_samples where activity_id = ?
            """,
        ).param(activityId).query { rs, _ ->
            val secs = PgArrays.parseDoubles(rs.getString("secs"))!!
            for (i in secs.indices) if (secs[i].isNaN()) secs[i] = 0.0
            SampleSeries(
                secs = secs.asList(),
                power = PgArrays.parseInts(rs.getString("power"))?.let(PgArrays::view),
                heartRate = PgArrays.parseInts(rs.getString("heart_rate"))?.let(PgArrays::view),
                cadence = PgArrays.parseInts(rs.getString("cadence"))?.let(PgArrays::view),
                distanceM = PgArrays.parseDoubles(rs.getString("distance_m"))?.let(PgArrays::view),
                altitudeM = PgArrays.parseDoubles(rs.getString("altitude_m"))?.let(PgArrays::view),
            )
        }.optional().orElse(null)

    // 지표 행과 mmp JSON 원문을 함께 돌려준다(JSON 해석은 서비스가).
    fun metrics(activityId: UUID): Pair<MetricsRow, String>? =
        jdbc.sql("select * from activity_metrics where activity_id = ?").param(activityId).query { rs, _ ->
            mapMetrics(rs) to rs.getString("mmp")
        }.optional().orElse(null)

    // 여러 활동의 지표를 쿼리 한 번으로 읽는다(목록 화면에서 활동마다 쿼리를 보내지 않도록). id 목록을 uuid
    // 배열 리터럴 '{a,b,...}'로 만들어 any(?::uuid[])에 바인딩한다. id는 DB에서 읽은 UUID 값이라 리터럴에
    // 다른 문자가 섞이지 않는다.
    fun metricsFor(activityIds: Collection<UUID>): Map<UUID, Pair<MetricsRow, String>> {
        if (activityIds.isEmpty()) return emptyMap()
        val literal = activityIds.joinToString(",", "{", "}")
        return jdbc.sql("select * from activity_metrics where activity_id = any(?::uuid[])").param(literal)
            .query { rs, _ -> rs.getObject("activity_id", UUID::class.java) to (mapMetrics(rs) to rs.getString("mmp")) }
            .list().toMap()
    }

    // PMC 입력: 현지 날짜별 TSS 합. TSS가 없는 활동(FTP 미등록, 파워 없음)은 빠진다. 시작 시각을 at time zone
    // <선수 시간대>로 바꾼 뒤 날짜로 잘라 DB 세션 시간대와 상관없이 같은 날짜가 나온다(2026-10-15 전에는 'UTC' 고정).
    // 시간대 이름은 java.time.ZoneId가 받아들인 값만 오고(BadRequestException으로 검사), ?로 바인딩한다.
    /** 두 날짜 사이(양 끝 포함)의 현지 날짜별 TSS 합. */
    fun dailyTss(athleteId: UUID, from: java.time.LocalDate, to: java.time.LocalDate, zone: java.time.ZoneId): Map<java.time.LocalDate, Double> =
        jdbc.sql(
            """
            select (a.started_at at time zone ?)::date as day, sum(m.tss) as tss
            from activity a join activity_metrics m on m.activity_id = a.id
            where a.athlete_id = ? and m.tss is not null
              and (a.started_at at time zone ?)::date between ? and ?
            group by day
            """,
        ).params(zone.id, athleteId, zone.id, from, to)
            .query { rs, _ -> rs.getObject("day", java.time.LocalDate::class.java) to rs.getDouble("tss") }
            .list().toMap()

    /** 기간 [from, to) 안에 시작한 파워 활동 수(FTP 추정 근거의 라이딩 수). */
    fun countPowerActivities(athleteId: UUID, from: OffsetDateTime, to: OffsetDateTime): Int =
        jdbc.sql("select count(*) from activity where athlete_id = ? and has_power and started_at >= ? and started_at < ?")
            .params(athleteId, from, to).query(Long::class.java).single().toInt()

    /** 선수의 첫 활동 날짜(현지). 활동이 없으면 null. 훈련 부하 워밍업의 시작점이다. */
    fun firstActivityDate(athleteId: UUID, zone: java.time.ZoneId): java.time.LocalDate? =
        jdbc.sql("select min((started_at at time zone ?)::date) from activity where athlete_id = ?")
            .params(zone.id, athleteId).query(java.time.LocalDate::class.java).optional().orElse(null)

    // 기간 최대 파워 커브: 기간 [from, to) 안 활동들의 mmp jsonb를 jsonb_each로 (키, 값) 행으로 펼쳐 키별
    // 최댓값을 구한다. 값이 null인 구간은 jsonb_typeof = 'number' 조건으로 걸러진다. jsonb 숫자는 text를 거쳐
    // double precision으로 바꾼다.
    /** 기간 [from, to) 안에 시작한 활동들에 저장된 MMP 값의 구간별 최댓값. */
    fun maxMmp(athleteId: UUID, from: OffsetDateTime, to: OffsetDateTime): Map<String, Double> =
        jdbc.sql(
            """
            select kv.key as k, max((kv.value)::text::double precision) as v
            from activity a join activity_metrics m on m.activity_id = a.id, jsonb_each(m.mmp) kv
            where a.athlete_id = ? and a.started_at >= ? and a.started_at < ? and jsonb_typeof(kv.value) = 'number'
            group by kv.key
            """,
        ).params(athleteId, from, to).query { rs, _ -> rs.getString("k") to rs.getDouble("v") }.list().toMap()

    // 지운 행이 있으면 true. 샘플·지표는 on delete cascade로 함께 지워진다.
    fun delete(id: UUID): Boolean = jdbc.sql("delete from activity where id = ?").param(id).update() > 0

    // ResultSet 한 행 → ActivityRow. nullable 숫자 컬럼은 PgArrays.kt의 doubleOrNull 등으로 읽는다(PgJDBC가
    // float4 컬럼을 Double 객체로 바로 읽지 못하는 문제).
    private fun mapActivity(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = ActivityRow(
        id = rs.getObject("id", UUID::class.java),
        athleteId = rs.getObject("athlete_id", UUID::class.java),
        startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
        source = rs.getString("source"),
        sourceName = rs.getString("source_name"),
        sport = rs.getString("sport"),
        recIntSecs = rs.doubleOrNull("rec_int_secs"),
        intervalShare = rs.doubleOrNull("interval_share"),
        sampleCount = rs.getInt("sample_count"),
        durationSecs = rs.doubleOrNull("duration_secs"),
        manufacturer = rs.intOrNull("manufacturer"),
        product = rs.intOrNull("product"),
        crcValid = rs.boolOrNull("crc_valid"),
        hasPower = rs.getBoolean("has_power"),
        powerMissing = rs.getInt("power_missing"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
    )

    // 지표 행 매핑. mmp는 여기서 비워 두고 서비스가 JSON 원문으로 채운다.
    private fun mapMetrics(rs: ResultSet) = MetricsRow(
        metricsVersion = rs.getInt("metrics_version"),
        ftpUsed = rs.intOrNull("ftp_used"),
        normalizedPower = rs.doubleOrNull("normalized_power"),
        intensityFactor = rs.doubleOrNull("intensity_factor"),
        tss = rs.doubleOrNull("tss"),
        averagePower = rs.doubleOrNull("average_power"),
        maxPower = rs.intOrNull("max_power"),
        workKj = rs.doubleOrNull("work_kj"),
        mmp = emptyMap(), // JSON 텍스트는 서비스 계층이 해석해 채운다
    )
}
