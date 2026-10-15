package com.rideinsight.fit

// [파일 역할] FIT 업로드 경로의 고정 seed 퍼징 테스트(DB 없음). 바이트 변이 4,000건과 구조를 아는 무작위 파일
// 2,000건을 업로드의 DB 앞 단계(ActivityService.prepare: 디코딩 → 종목·시각 검사 → 저장할 시계열)와 지표
// 계산(MetricsService.computeWithFtp: NP·TSS·MMP·파워 커브)에 넣고 '예외 계약'을 확인한다.
// [계약] (1) 예외는 정해 둔 네 가지만: FitDecodeException(400 invalid_fit), FitLimitException(413),
// InvalidActivityException(400 invalid_activity), UnsupportedSportException(422). 그 밖의 예외(예: 배열 범위,
// DateTimeException, IllegalArgumentException)는 API에서 500이 되므로 실패다. (2) 받아들인 파일의 시계열은 DB 열에
// 들어갈 수 있어야 한다: 파워·심박·케이던스는 smallint, 거리·고도·경과 시간은 real(float4) 범위, 시작 시각은 FIT
// date_time 범위(1989-12-31 ~ 2126-02-07). 넘으면 저장할 때 PostgreSQL 오류로 500이 된다. (3) 입력 하나는 3초
// 안에 끝나고, 전체는 4분 안에 끝난다(무한 루프 감시).
// [재현] 실패 메시지에 종류·반복 번호·씨앗 파일을 적는다. seed가 고정이라 같은 번호로 같은 입력을 다시 만든다.
// [결과 기록] 처음 실행에서 잡힌 것과 고친 것은 docs/ai-verification.md 2026-10-15 항목.

import com.rideinsight.service.ActivityService
import com.rideinsight.service.InvalidActivityException
import com.rideinsight.service.MetricsService
import com.rideinsight.service.PreparedActivity
import com.rideinsight.service.UnsupportedSportException
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively

class FitFuzzTest {
    private val seeds = FitFuzz.seeds()

    // FIT date_time(uint32 초, 기준 1989-12-31T00:00:00Z)가 나타낼 수 있는 범위.
    private val fitEpoch = OffsetDateTime.of(1989, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC)
    private val fitMax = fitEpoch.plusSeconds(0xFFFFFFFFL)
    private val realMax = Float.MAX_VALUE.toDouble()

    // 받아들인 활동이 DB 열에 들어갈 수 있는지(계약 2). 어기면 무엇이 어긋났는지 문장으로 돌려준다.
    private fun storageViolation(p: PreparedActivity): String? {
        if (p.startedAt.isBefore(fitEpoch) || p.startedAt.isAfter(fitMax)) return "startedAt ${p.startedAt} outside FIT date_time"
        val s = p.series
        for ((name, col) in listOf("power" to s.power, "heartRate" to s.heartRate, "cadence" to s.cadence)) {
            col?.forEachIndexed { i, v -> if (v != null && v !in Short.MIN_VALUE..Short.MAX_VALUE) return "$name[$i]=$v outside smallint" }
        }
        for ((name, col) in listOf("distance" to s.distanceM, "altitude" to s.altitudeM)) {
            col?.forEachIndexed { i, v -> if (v != null && !(v.isFinite() && abs(v) <= realMax)) return "$name[$i]=$v outside real" }
        }
        s.secs.forEachIndexed { i, v -> if (!(v.isFinite() && abs(v) <= realMax)) return "secs[$i]=$v outside real" }
        return null
    }

    // 입력 하나를 업로드 경로에 넣고 결과 종류를 돌려준다. 계약을 어기면 failures에 사유를 더한다.
    private fun run(label: String, bytes: ByteArray, failures: MutableList<String>, outcomes: MutableMap<String, Int>) {
        val outcome = try {
            val p = ActivityService.prepare(bytes)
            storageViolation(p)?.let { failures += "$label: $it" }
            if (p.hasPower) {
                val m = MetricsService.computeWithFtp(p.series, 250)
                if (m.curve!!.size > 300) failures += "$label: curve has ${m.curve!!.size} points"
                val values = listOfNotNull(m.normalizedPower, m.tss, m.averagePower, m.workKj) + m.mmp.values.filterNotNull()
                if (values.any { !it.isFinite() }) failures += "$label: non-finite metric $values"
            }
            "accepted"
        } catch (e: FitLimitException) {
            "413 fit_limit_exceeded"
        } catch (e: FitDecodeException) {
            "400 invalid_fit"
        } catch (e: InvalidActivityException) {
            "400 invalid_activity"
        } catch (e: UnsupportedSportException) {
            "422 unsupported_sport"
        } catch (e: Throwable) {
            failures += "$label: ${e::class.qualifiedName}: ${e.message}"
            "unexpected"
        }
        outcomes.merge(outcome, 1, Int::plus)
    }

    private fun fuzz(name: String, count: Int, seed: Long, make: (Random, Int) -> Pair<String, ByteArray>) {
        val failures = ArrayList<String>()
        val outcomes = sortedMapOf<String, Int>()
        var slowest = 0L
        assertTimeoutPreemptively(Duration.ofMinutes(4)) {
            val rng = Random(seed)
            for (i in 0 until count) {
                val (source, bytes) = make(rng, i)
                val t0 = System.nanoTime()
                run("$name #$i from $source", bytes, failures, outcomes)
                val ms = (System.nanoTime() - t0) / 1_000_000
                slowest = maxOf(slowest, ms)
                if (ms > 3_000) failures += "$name #$i from $source took $ms ms"
            }
        }
        // 위반을 종류별로 센다(예: 'heartRate outside smallint'). 숫자·위치를 지운 사유로 묶는다.
        val kinds = failures.groupingBy { f ->
            f.substringAfter(": ").replace(Regex("""\[\d+]=\S+"""), "").replace(Regex("[0-9]+"), "N")
        }.eachCount()
        println("FIT_FUZZ $name n=$count outcomes=$outcomes slowest_ms=$slowest failures=${failures.size} kinds=$kinds")
        assertTrue(failures.isEmpty(), "${failures.size} contract violations, first ones:\n" + failures.take(15).joinToString("\n"))
    }

    // 실제 샘플과 테스트용 파일을 무작위로 망가뜨린 4,000건.
    @Test
    fun `byte mutations of real files only raise the documented errors`() =
        fuzz("mutation", 4_000, 20261015L) { rng, _ ->
            val (source, bytes) = seeds[rng.nextInt(seeds.size)]
            source to FitFuzz.mutate(bytes, rng)
        }

    // 무작위 생성으로는 드물게만 나오는 경우를 직접 만든다: record가 하나뿐이고 timestamp가 uint32가 아닌 타입(부호
    // 있는 32비트, 64비트)의 극단값인 파일. FIT date_time(uint32) 밖의 시각은 시작 시각을 만들 수 없거나(Long 넘침 →
    // DateTimeException) DB 범위를 벗어나므로 400이어야 한다. 고치기 전에는 7건 중 4건이 받아들여졌고 그중 하나는
    // 시작 시각이 서기 4461783년이었다(저장하면 PostgreSQL 범위 오류로 500).
    @Test
    fun `timestamps outside the FIT date_time range are rejected as invalid FIT`() {
        val cases = listOf(
            0x85 to Int.MIN_VALUE.toLong(), 0x85 to -1L, 0x8E to Long.MAX_VALUE, 0x8E to Long.MIN_VALUE,
            0x8F to -1L, 0x8F to 0x1_0000_0000L, 0x8E to 0x7FFF_FFFF_FFFFL,
        )
        val failures = ArrayList<String>()
        val outcomes = sortedMapOf<String, Int>()
        for ((type, value) in cases) {
            val size = if (type == 0x85) 4 else 8
            val w = FitWriter()
            w.define(0, FitProfile.MSG_SESSION, listOf(Triple(FitProfile.Session.SPORT, 1, 0x00)))
            w.data(0, listOf(FitProfile.Sport.CYCLING))
            w.define(1, FitProfile.MSG_RECORD, listOf(Triple(FitProfile.FIELD_TIMESTAMP, size, type), Triple(FitProfile.Record.POWER, 2, 0x84)))
            w.data(1, listOf(value, 200L))
            run("timestamp type=0x${type.toString(16)} value=$value", w.build(), failures, outcomes)
        }
        println("FIT_FUZZ timestamps outcomes=$outcomes failures=$failures")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        // 값이 그 타입의 무효 값(sint64 최댓값, uint64 전부 1)이거나 열 저장소의 '값 없음' 표시(Long 최솟값)와 같으면
        // timestamp가 없는 record가 되어 건너뛰고, record가 남지 않아 400 invalid_activity다. 어느 쪽이든 400이고
        // 받아들여 저장하는 경우는 없어야 한다.
        assertTrue(outcomes.keys.all { it == "400 invalid_fit" || it == "400 invalid_activity" }, "every case must be a 400: $outcomes")
    }

    // 구조는 올바르고 record 필드의 타입·값이 무작위인 2,000건(디코딩을 통과해 후처리까지 가는 입력).
    @Test
    fun `structured random files only raise the documented errors and stay storable`() =
        fuzz("structured", 2_000, 20261016L) { rng, _ -> "structured" to FitFuzz.structured(rng) }
}
