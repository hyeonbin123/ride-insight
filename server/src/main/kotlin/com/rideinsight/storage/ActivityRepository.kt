package com.rideinsight.storage

import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

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

class SampleSeries(
    val secs: List<Double>,
    val power: List<Int?>?,
    val heartRate: List<Int?>?,
    val cadence: List<Int?>?,
    val distanceM: List<Double?>?,
    val altitudeM: List<Double?>?,
)

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
)

class DuplicateActivityException(message: String) : RuntimeException(message)

@Repository
class ActivityRepository(private val jdbc: JdbcClient) {
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

    fun insert(a: NewActivity, samples: SampleSeries): UUID {
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

    fun saveMetrics(activityId: UUID, m: MetricsRow, mmpJson: String) {
        jdbc.sql(
            """
            insert into activity_metrics (activity_id, metrics_version, ftp_used, normalized_power, intensity_factor, tss,
                average_power, max_power, work_kj, mmp)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            on conflict (activity_id) do update set metrics_version = excluded.metrics_version, ftp_used = excluded.ftp_used,
                normalized_power = excluded.normalized_power, intensity_factor = excluded.intensity_factor, tss = excluded.tss,
                average_power = excluded.average_power, max_power = excluded.max_power, work_kj = excluded.work_kj,
                mmp = excluded.mmp, computed_at = now()
            """,
        ).params(
            listOf(
                activityId, m.metricsVersion, m.ftpUsed, m.normalizedPower, m.intensityFactor, m.tss,
                m.averagePower, m.maxPower, m.workKj, mmpJson,
            ),
        ).update()
    }

    fun find(id: UUID): ActivityRow? =
        jdbc.sql("select * from activity where id = ?").param(id).query(::mapActivity).optional().orElse(null)

    fun list(athleteId: UUID, before: OffsetDateTime?, limit: Int): List<ActivityRow> =
        jdbc.sql(
            """
            select * from activity where athlete_id = ? and (?::timestamptz is null or started_at < ?::timestamptz)
            order by started_at desc limit ?
            """,
        ).params(listOf(athleteId, before, before, limit)).query(::mapActivity).list()

    /** Activities whose start date (UTC) is on or after [fromDate], oldest first; used to recompute TSS. */
    fun listFrom(athleteId: UUID, from: OffsetDateTime): List<ActivityRow> =
        jdbc.sql("select * from activity where athlete_id = ? and started_at >= ? order by started_at")
            .params(athleteId, from).query(::mapActivity).list()

    fun samples(activityId: UUID): SampleSeries? =
        jdbc.sql("select * from activity_samples where activity_id = ?").param(activityId).query { rs, _ ->
            SampleSeries(
                secs = PgArrays.readDoubles(rs, "secs")!!.map { it ?: 0.0 },
                power = PgArrays.readInts(rs, "power"),
                heartRate = PgArrays.readInts(rs, "heart_rate"),
                cadence = PgArrays.readInts(rs, "cadence"),
                distanceM = PgArrays.readDoubles(rs, "distance_m"),
                altitudeM = PgArrays.readDoubles(rs, "altitude_m"),
            )
        }.optional().orElse(null)

    fun metrics(activityId: UUID): Pair<MetricsRow, String>? =
        jdbc.sql("select * from activity_metrics where activity_id = ?").param(activityId).query { rs, _ ->
            mapMetrics(rs) to rs.getString("mmp")
        }.optional().orElse(null)

    fun metricsFor(activityIds: Collection<UUID>): Map<UUID, Pair<MetricsRow, String>> {
        if (activityIds.isEmpty()) return emptyMap()
        val literal = activityIds.joinToString(",", "{", "}")
        return jdbc.sql("select * from activity_metrics where activity_id = any(?::uuid[])").param(literal)
            .query { rs, _ -> rs.getObject("activity_id", UUID::class.java) to (mapMetrics(rs) to rs.getString("mmp")) }
            .list().toMap()
    }

    /** Daily TSS totals (UTC dates) between two dates inclusive. */
    fun dailyTss(athleteId: UUID, from: java.time.LocalDate, to: java.time.LocalDate): Map<java.time.LocalDate, Double> =
        jdbc.sql(
            """
            select (a.started_at at time zone 'UTC')::date as day, sum(m.tss) as tss
            from activity a join activity_metrics m on m.activity_id = a.id
            where a.athlete_id = ? and m.tss is not null
              and (a.started_at at time zone 'UTC')::date between ? and ?
            group by day
            """,
        ).params(athleteId, from, to)
            .query { rs, _ -> rs.getObject("day", java.time.LocalDate::class.java) to rs.getDouble("tss") }
            .list().toMap()

    /** UTC date of the athlete's earliest activity, or null. */
    fun firstActivityDate(athleteId: UUID): java.time.LocalDate? =
        jdbc.sql("select min((started_at at time zone 'UTC')::date) from activity where athlete_id = ?")
            .param(athleteId).query(java.time.LocalDate::class.java).optional().orElse(null)

    /** Per-window maximum of the stored MMP values over activities started in [from, to). */
    fun maxMmp(athleteId: UUID, from: OffsetDateTime, to: OffsetDateTime): Map<String, Double> =
        jdbc.sql(
            """
            select kv.key as k, max((kv.value)::text::double precision) as v
            from activity a join activity_metrics m on m.activity_id = a.id, jsonb_each(m.mmp) kv
            where a.athlete_id = ? and a.started_at >= ? and a.started_at < ? and jsonb_typeof(kv.value) = 'number'
            group by kv.key
            """,
        ).params(athleteId, from, to).query { rs, _ -> rs.getString("k") to rs.getDouble("v") }.list().toMap()

    fun delete(id: UUID): Boolean = jdbc.sql("delete from activity where id = ?").param(id).update() > 0

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

    private fun mapMetrics(rs: ResultSet) = MetricsRow(
        metricsVersion = rs.getInt("metrics_version"),
        ftpUsed = rs.intOrNull("ftp_used"),
        normalizedPower = rs.doubleOrNull("normalized_power"),
        intensityFactor = rs.doubleOrNull("intensity_factor"),
        tss = rs.doubleOrNull("tss"),
        averagePower = rs.doubleOrNull("average_power"),
        maxPower = rs.intOrNull("max_power"),
        workKj = rs.doubleOrNull("work_kj"),
        mmp = emptyMap(), // parsed from the JSON text by the service layer
    )
}
