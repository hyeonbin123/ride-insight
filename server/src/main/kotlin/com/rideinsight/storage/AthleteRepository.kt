package com.rideinsight.storage

import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

class Athlete(
    val id: UUID,
    val name: String,
    val gender: String?,
    val birthYear: Int?,
    val weightKg: Double?,
    val createdAt: OffsetDateTime,
)

class Threshold(val effectiveFrom: LocalDate, val ftpWatts: Int, val source: String)

@Repository
class AthleteRepository(private val jdbc: JdbcClient) {
    fun insert(name: String, gender: String?, birthYear: Int?, weightKg: Double?): Athlete {
        val id = jdbc.sql(
            "insert into athlete (name, gender, birth_year, weight_kg) values (?, ?, ?, ?) returning id",
        ).params(listOf(name, gender, birthYear, weightKg)).query(UUID::class.java).single()
        return find(id)!!
    }

    fun find(id: UUID): Athlete? =
        jdbc.sql("select * from athlete where id = ?").param(id).query(::mapAthlete).optional().orElse(null)

    fun exists(id: UUID): Boolean =
        jdbc.sql("select count(*) from athlete where id = ?").param(id).query(Long::class.java).single() > 0

    fun setThreshold(athleteId: UUID, effectiveFrom: LocalDate, ftpWatts: Int, source: String) {
        jdbc.sql(
            """
            insert into athlete_threshold (athlete_id, effective_from, ftp_watts, source)
            values (?, ?, ?, ?)
            on conflict (athlete_id, effective_from) do update set ftp_watts = excluded.ftp_watts, source = excluded.source
            """,
        ).params(athleteId, effectiveFrom, ftpWatts, source).update()
    }

    /** FTP in force on [date]: the most recent threshold whose effective_from is not after it. */
    fun thresholdOn(athleteId: UUID, date: LocalDate): Threshold? =
        jdbc.sql(
            """
            select effective_from, ftp_watts, source from athlete_threshold
            where athlete_id = ? and effective_from <= ? order by effective_from desc limit 1
            """,
        ).params(athleteId, date).query(::mapThreshold).optional().orElse(null)

    fun thresholds(athleteId: UUID): List<Threshold> =
        jdbc.sql("select effective_from, ftp_watts, source from athlete_threshold where athlete_id = ? order by effective_from")
            .param(athleteId).query(::mapThreshold).list()

    private fun mapAthlete(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Athlete(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        gender = rs.getString("gender"),
        birthYear = rs.intOrNull("birth_year"),
        weightKg = rs.doubleOrNull("weight_kg"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
    )

    private fun mapThreshold(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Threshold(
        effectiveFrom = rs.getObject("effective_from", LocalDate::class.java),
        ftpWatts = rs.getInt("ftp_watts"),
        source = rs.getString("source"),
    )
}
