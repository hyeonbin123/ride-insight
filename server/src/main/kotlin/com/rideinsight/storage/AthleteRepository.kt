package com.rideinsight.storage

// [파일 역할] athlete·athlete_threshold 테이블 저장소: 선수 생성·조회·존재 확인·행 잠금, 날짜별 FTP 이력
// 저장과 '그 날짜에 유효한 FTP' 조회.
// [설계 이유] FTP를 선수 컬럼 하나가 아니라 날짜별 이력 테이블로 둔다(docs/api.md 저장 모델: 그 날짜의 FTP는
// effective_from ≤ 날짜인 가장 최근 행; docs/ai-verification.md 2단계의 사람이 정한 것). 그래서 과거 활동은
// 그 당시 FTP로, 새 활동은 새 FTP로 IF·TSS를 계산한다.
// [연결] AthleteService, ActivityService(잠금), MetricsService(그 날짜의 FTP), tools/ImportOpenData(이력
// 가져오기)가 쓴다.
// [잠금 대기 상한] 선수 행 잠금([AthleteRepository.lock])은 트랜잭션 단위 lock_timeout(설정 ride-insight.athlete-lock-timeout,
// 기본 5초) 안에 얻지 못하면 [AthleteBusyException](API 409 athlete_busy)이다(2026-10-24). 그 전에는 상한이 없어, 같은 선수의
// 요청이 잠금을 쥔 쪽이 끝날 때까지 커넥션(업로드는 UploadGate 허가까지)을 쥔 채 기다렸다.

import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.ceil
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
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
    // 현지 날짜를 셀 때 쓰는 시간대(IANA 이름, V3__athlete_time_zone.sql). 기본 Asia/Seoul.
    val timeZone: String,
)

// FTP 이력 한 줄: 적용 시작일, 와트, 출처(manual, import, estimate).
class Threshold(val effectiveFrom: LocalDate, val ftpWatts: Int, val source: String)

/**
 * 같은 선수의 다른 쓰기 요청(업로드 저장, FTP·시간대 변경, 삭제)이 선수 행을 잡고 있어 잠금 대기 상한 안에 차례가 오지
 * 않았다 → API 409 `athlete_busy` + `Retry-After`(초). 서버 오류가 아니라 잠시 뒤 같은 요청을 다시 보내면 되는 상태다.
 * [AthleteRepository.lock]이 PostgreSQL의 lock_not_available(SQLSTATE 55P03)을 이 예외로 바꿔 던지고, 저장소 예외를
 * HTTP로 바꾸는 일은 DuplicateActivityException처럼 api/ApiExceptionHandler가 맡는다.
 */
class AthleteBusyException(val retryAfterSeconds: Int) :
    RuntimeException("another request is updating this athlete; retry after $retryAfterSeconds seconds")

// lockTimeout: 선수 행 잠금을 기다리는 상한(application.properties의 ride-insight.athlete-lock-timeout, 기본 5초). Spring의
// Duration 표기(5s, 500ms)로 받는다. 0이면 상한 없음(PostgreSQL lock_timeout 규칙), 음수나 int 밀리초를 넘는 값은 거절한다.
@Repository
class AthleteRepository(
    private val jdbc: JdbcClient,
    @Value("\${ride-insight.athlete-lock-timeout:5s}") private val lockTimeout: Duration,
) {
    init {
        require(!lockTimeout.isNegative && lockTimeout.toMillis() <= Int.MAX_VALUE) {
            "ride-insight.athlete-lock-timeout must be between 0 and ${Int.MAX_VALUE} ms"
        }
    }

    companion object {
        /** PostgreSQL SQLSTATE lock_not_available: lock_timeout 안에 잠금을 얻지 못해 문장이 취소됐다. */
        const val LOCK_NOT_AVAILABLE = "55P03"
    }

    // 선수를 넣고(returning id) 다시 읽어 DB가 채운 created_at까지 담아 돌려준다.
    fun insert(name: String, gender: String?, birthYear: Int?, weightKg: Double?, timeZone: String): Athlete {
        val id = jdbc.sql(
            "insert into athlete (name, gender, birth_year, weight_kg, time_zone) values (?, ?, ?, ?, ?) returning id",
        ).params(listOf(name, gender, birthYear, weightKg, timeZone)).query(UUID::class.java).single()
        return find(id)!!
    }

    // 선수의 시간대를 바꾼다. 바꾼 행이 없으면(선수 없음) false.
    fun setTimeZone(id: UUID, timeZone: String): Boolean =
        jdbc.sql("update athlete set time_zone = ? where id = ?").params(timeZone, id).update() > 0

    fun find(id: UUID): Athlete? =
        jdbc.sql("select * from athlete where id = ?").param(id).query(::mapAthlete).optional().orElse(null)

    /**
     * 현재 트랜잭션 동안 선수 행을 잠가, 한 선수의 업로드 저장·FTP 변경·시간대 변경·삭제가 한 번에 하나씩 실행되게 한다
     * (2026-10-10 Codex 검토: FTP 변경과 겹친 업로드가 옛 FTP로 계산한 지표를 그대로 남길 수 있었다. 2026-10-24: 잠금 없이
     * 지우던 삭제가 재계산과 겹치면 FK 위반으로 500이 될 수 있어 삭제도 이 잠금을 쓴다).
     * 선수가 없으면 아무것도 잠그지 않고 false를 돌려준다. 잠금을 [lockTimeout] 안에 얻지 못하면 [AthleteBusyException].
     *
     * 잠금은 트랜잭션이 끝날 때 풀리므로 트랜잭션 안에서만 의미가 있다. ActivityService(업로드 저장 트랜잭션, 삭제)와
     * AthleteService.setThreshold·setTimeZone이 트랜잭션 안에서 무엇이든 쓰기 전에 부른다.
     *
     * 대기 상한은 PostgreSQL lock_timeout을 이 트랜잭션에만(set_config의 세 번째 인자 true = SET LOCAL) 걸어 둔다. 트랜잭션이
     * 끝나면 풀리므로 커넥션 풀로 돌아간 커넥션의 다른 요청에 남지 않는다. SET 문은 값을 바인딩할 수 없어 set_config 함수를
     * 쓴다. statement_timeout은 쓰지 않는다: 잠금 대기가 아니라 문장 실행 시간을 묶는 것이고, 재계산처럼 짧은 문장이 많은 작업의
     * 총시간도 묶지 못한다.
     */
    fun lock(id: UUID): Boolean {
        jdbc.sql("select set_config('lock_timeout', ?, true)").param("${lockTimeout.toMillis()}ms").query(String::class.java).single()
        return try {
            jdbc.sql("select id from athlete where id = ? for update").param(id).query(UUID::class.java).optional().isPresent
        } catch (e: DataAccessException) {
            // 55P03은 예외 타입이 아니라 SQLSTATE로 가른다. Spring 7의 기본 변환기(SQLExceptionSubclassTranslator → SQLState
            // SQLExceptionTranslator, spring-jdbc 7.0.9의 변환표 확인)는 SQLSTATE 55 클래스를 알지 못해 이 오류를
            // CannotAcquireLockException이 아니라 UncategorizedSQLException으로 넘기기 때문이다.
            val state = (e.mostSpecificCause as? SQLException)?.sqlState
            if (state == LOCK_NOT_AVAILABLE) throw AthleteBusyException(retryAfterSeconds())
            throw e
        }
    }

    // 409 응답의 Retry-After(초): 잠금 대기 상한을 초로 올린 값, 최소 1. 지금 잠금을 쥔 요청(업로드 저장은 길어야 수 초,
    // 재계산·삭제는 밀리초)이 그 안에 끝날 가능성이 높다.
    private fun retryAfterSeconds(): Int = maxOf(1, ceil(lockTimeout.toMillis() / 1000.0).toInt())

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
        timeZone = rs.getString("time_zone"),
    )

    private fun mapThreshold(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Threshold(
        effectiveFrom = rs.getObject("effective_from", LocalDate::class.java),
        ftpWatts = rs.getInt("ftp_watts"),
        source = rs.getString("source"),
    )
}
