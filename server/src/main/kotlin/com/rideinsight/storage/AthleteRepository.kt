package com.rideinsight.storage

// [파일 역할] athlete·athlete_threshold 테이블 저장소: 선수 생성·조회·존재 확인·행 잠금, 날짜별 FTP 이력
// 저장과 '그 날짜에 유효한 FTP' 조회.
// [설계 이유] FTP를 선수 컬럼 하나가 아니라 날짜별 이력 테이블로 둔다(docs/api.md 저장 모델: 그 날짜의 FTP는
// effective_from ≤ 날짜인 가장 최근 행; docs/ai-verification.md 2단계의 사람이 정한 것). 그래서 과거 활동은
// 그 당시 FTP로, 새 활동은 새 FTP로 IF·TSS를 계산한다.
// [연결] AthleteService, ActivityService(잠금), MetricsService(그 날짜의 FTP), tools/ImportOpenData(이력
// 가져오기)가 쓴다.

import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

// athlete 테이블 한 행.
class Athlete(
    val id: UUID,
    val name: String,
    val gender: String?,
    val birthYear: Int?,
    val weightKg: Double?,
    val createdAt: OffsetDateTime,
)

// FTP 이력 한 줄: 적용 시작일, 와트, 출처(manual, import, estimate).
class Threshold(val effectiveFrom: LocalDate, val ftpWatts: Int, val source: String)

@Repository
class AthleteRepository(private val jdbc: JdbcClient) {
    // 선수를 넣고(returning id) 다시 읽어 DB가 채운 created_at까지 담아 돌려준다.
    fun insert(name: String, gender: String?, birthYear: Int?, weightKg: Double?): Athlete {
        val id = jdbc.sql(
            "insert into athlete (name, gender, birth_year, weight_kg) values (?, ?, ?, ?) returning id",
        ).params(listOf(name, gender, birthYear, weightKg)).query(UUID::class.java).single()
        return find(id)!!
    }

    fun find(id: UUID): Athlete? =
        jdbc.sql("select * from athlete where id = ?").param(id).query(::mapAthlete).optional().orElse(null)

    /**
     * 현재 트랜잭션 동안 선수 행을 잠가, 한 선수의 업로드와 FTP 변경이 한 번에 하나씩 실행되게 한다
     * (2026-10-10 Codex 검토: FTP 변경과 겹친 업로드가 옛 FTP로 계산한 지표를 그대로 남길 수 있었다).
     * 선수가 없으면 아무것도 잠그지 않고 false를 돌려준다.
     *
     * 잠금은 트랜잭션이 끝날 때 풀리므로 트랜잭션 안에서만 의미가 있다. @Transactional인
     * ActivityService.ingestFit과 AthleteService.setThreshold가 맨 처음에 부른다.
     */
    fun lock(id: UUID): Boolean =
        jdbc.sql("select id from athlete where id = ? for update").param(id).query(UUID::class.java).optional().isPresent

    // 선수가 있는지(count > 0).
    fun exists(id: UUID): Boolean =
        jdbc.sql("select count(*) from athlete where id = ?").param(id).query(Long::class.java).single() > 0

    // FTP 이력 upsert: 기본 키가 (athlete_id, effective_from)라 같은 날짜가 있으면 값과 출처를 덮어쓴다.
    fun setThreshold(athleteId: UUID, effectiveFrom: LocalDate, ftpWatts: Int, source: String) {
        jdbc.sql(
            """
            insert into athlete_threshold (athlete_id, effective_from, ftp_watts, source)
            values (?, ?, ?, ?)
            on conflict (athlete_id, effective_from) do update set ftp_watts = excluded.ftp_watts, source = excluded.source
            """,
        ).params(athleteId, effectiveFrom, ftpWatts, source).update()
    }

    /**
     * [date]에 유효한 FTP: effective_from이 그 날짜보다 늦지 않은 이력 중 가장 최근 행 하나. 없으면 null이고,
     * 그러면 그 활동의 IF·TSS는 계산되지 않는다.
     */
    fun thresholdOn(athleteId: UUID, date: LocalDate): Threshold? =
        jdbc.sql(
            """
            select effective_from, ftp_watts, source from athlete_threshold
            where athlete_id = ? and effective_from <= ? order by effective_from desc limit 1
            """,
        ).params(athleteId, date).query(::mapThreshold).optional().orElse(null)

    // 이력 전체(적용일 오름차순).
    fun thresholds(athleteId: UUID): List<Threshold> =
        jdbc.sql("select effective_from, ftp_watts, source from athlete_threshold where athlete_id = ? order by effective_from")
            .param(athleteId).query(::mapThreshold).list()

    // 행 매핑. birth_year·weight_kg(real)는 nullable이라 intOrNull·doubleOrNull로 읽는다.
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
