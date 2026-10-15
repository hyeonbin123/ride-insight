package com.rideinsight.fit

// [파일 역할] FIT 업로드 경로의 퍼징 도구. 두 가지 입력을 만든다.
//  (1) 바이트 변이: 실제 샘플 FIT와 테스트용 파일을 무작위로 망가뜨린다(비트 뒤집기, 특정 값으로 덮어쓰기,
//      바이트 끼워 넣기·지우기, 잘라내기, 구간 복제, 헤더의 데이터 크기 덮어쓰기). 대부분 구조 오류로 끝나므로
//      디코더의 '거절' 경로를 넓게 훑는다.
//  (2) 구조를 아는 생성: FitWriter로 구조는 올바르되 record 필드의 기본 타입(uint8~uint64, sint, float)과 값
//      (0, 최댓값, 음수, NaN, 무한대, 큰 시각 점프)을 무작위로 고른 파일을 만든다. 디코딩을 통과해 서비스의
//      후처리(시계열·지표·저장 형식)까지 가는 입력을 만든다. 바이트 변이로는 '크기 바이트와 타입 바이트를 함께
//      맞게 바꾸는' 경우가 거의 나오지 않기 때문이다.
// [재현성] 둘 다 seed를 받은 java.util.Random만 쓴다. 실패하면 seed와 반복 번호로 같은 입력을 다시 만들 수 있다.
// [연결] FitFuzzTest(디코더 + 서비스의 DB 앞 단계, 수천 건)와 TamperedFitTest(실제 HTTP 업로드, 수백 건)가 쓴다.
// 새 의존성(Jazzer 등)은 쓰지 않았다: 고정 seed 변이로 정해진 예외 계약을 확인하는 데는 표준 라이브러리로
// 충분하고, CI(Linux)와 이 PC(Windows)에서 똑같이 돈다.

import java.io.File
import java.util.Random

object FitFuzz {
    /** 변이의 씨앗이 되는 파일: 샘플 FIT 전부(손상 파일 포함)와 테스트용으로 만든 파일 세 개. */
    fun seeds(): List<Pair<String, ByteArray>> {
        val dir = File("src/test/resources/fit")
        val samples = dir.listFiles { f -> f.name.endsWith(".fit") }!!.sortedBy { it.name }.map { it.name to it.readBytes() }
        return samples + listOf(
            "generated-ride" to FitWriter.ride(300).build(),
            "tiny-records" to FitStressFiles.tinyRecords(3_000),
            "rich-records" to FitStressFiles.richRecords(2_000),
        )
    }

    // 덮어쓸 때 고르는 '경계' 바이트 값: 0, 최댓값, 부호 경계, 헤더 비트(정의 0x40, 압축 0x80, 개발자 0x20).
    private val INTERESTING = intArrayOf(0x00, 0xFF, 0x7F, 0x80, 0x01, 0x40, 0x20, 0xE0, 0x0E, 0x8E)

    /** [source]를 복사해 변이 1~4개를 적용한 바이트 배열. */
    fun mutate(source: ByteArray, rng: Random): ByteArray {
        var b = source.copyOf()
        repeat(1 + rng.nextInt(4)) {
            if (b.isEmpty()) return b
            // 앞쪽 256바이트(헤더와 첫 정의 메시지들이 있는 곳)를 절반의 확률로 고른다. 정의를 망가뜨려야 디코더의
            // 더 많은 분기를 지난다.
            val pos = if (rng.nextBoolean()) rng.nextInt(minOf(b.size, 256)) else rng.nextInt(b.size)
            b = when (rng.nextInt(7)) {
                0 -> b.also { it[pos] = (it[pos].toInt() xor (1 shl rng.nextInt(8))).toByte() }
                1 -> b.also { it[pos] = INTERESTING[rng.nextInt(INTERESTING.size)].toByte() }
                2 -> {
                    val extra = ByteArray(1 + rng.nextInt(16)).also(rng::nextBytes)
                    b.copyOfRange(0, pos) + extra + b.copyOfRange(pos, b.size)
                }
                3 -> b.copyOfRange(0, pos) + b.copyOfRange(minOf(b.size, pos + 1 + rng.nextInt(16)), b.size)
                4 -> b.copyOfRange(0, maxOf(1, pos))
                5 -> {
                    val len = minOf(b.size - pos, 1 + rng.nextInt(64))
                    b.copyOfRange(0, pos) + b.copyOfRange(pos, pos + len) + b.copyOfRange(pos, b.size)
                }
                else -> {
                    // 헤더의 데이터 크기(오프셋 4~7)를 실제 길이에 맞추거나 무작위 값으로 바꾼다. 맞추면 끼워 넣기·
                    // 지우기 뒤에도 디코더가 데이터 영역 끝까지 읽는다.
                    if (b.size >= 14) {
                        val headerSize = b[0].toInt() and 0xFF
                        val size = if (rng.nextBoolean()) b.size - headerSize - 2 else rng.nextInt()
                        b[4] = size.toByte(); b[5] = (size shr 8).toByte(); b[6] = (size shr 16).toByte(); b[7] = (size shr 24).toByte()
                    }
                    b
                }
            }
        }
        return b
    }

    // record 필드에 쓸 수 있는 (기본 타입 바이트, 크기). 0x80 비트는 다중 바이트 값의 엔디언 표시다.
    private val TYPES = listOf(
        0x02 to 1, 0x01 to 1, 0x0A to 1, 0x84 to 2, 0x83 to 2, 0x8B to 2, 0x86 to 4, 0x85 to 4, 0x8C to 4,
        0x88 to 4, 0x89 to 8, 0x8E to 8, 0x8F to 8, 0x90 to 8,
    )

    private val RECORD_FIELDS = intArrayOf(
        FitProfile.Record.POWER, FitProfile.Record.HEART_RATE, FitProfile.Record.CADENCE, FitProfile.Record.DISTANCE,
        FitProfile.Record.SPEED, FitProfile.Record.ALTITUDE, FitProfile.Record.ENHANCED_SPEED, FitProfile.Record.ENHANCED_ALTITUDE,
    )

    // 기본 타입에 맞는 무작위 값(원시 비트). 정수는 0·1·최댓값·최솟값·무효 값·보통 값, 실수는 NaN·무한대·음수·큰 값·보통 값.
    private fun value(type: Int, size: Int, rng: Random): Long {
        val base = type and 0x1F
        if (base == 8) {
            val f = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -5f, 1e30f, 250f, 0f)[rng.nextInt(7)]
            return java.lang.Float.floatToRawIntBits(f).toLong() and 0xFFFFFFFFL
        }
        if (base == 9) {
            val d = doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1e300, 250.0, 0.0)[rng.nextInt(6)]
            return java.lang.Double.doubleToRawLongBits(d)
        }
        val bits = size * 8
        return when (rng.nextInt(6)) {
            0 -> 0L
            1 -> 1L
            2 -> if (bits == 64) -1L else (1L shl bits) - 1 // 모든 비트 1(부호 없는 타입의 무효 값)
            3 -> if (bits == 64) Long.MAX_VALUE else (1L shl (bits - 1)) - 1
            4 -> if (bits == 64) Long.MIN_VALUE else (1L shl (bits - 1)) // 부호 있는 최솟값의 비트
            else -> rng.nextInt(400).toLong()
        }
    }

    /**
     * 구조는 올바르지만 record 필드의 타입과 값이 무작위인 FIT 파일. file_id·session(종목도 무작위: 거절
     * 종목 포함), record 정의(timestamp는 대개 uint32, 가끔 다른 타입), record 1~300개. 시각은 대개 1초씩
     * 늘지만 가끔 크게 뛰거나 거꾸로 간다.
     */
    fun structured(rng: Random): ByteArray {
        val w = FitWriter()
        w.define(0, FitProfile.MSG_FILE_ID, listOf(Triple(FitProfile.FileId.TYPE, 1, 0x00), Triple(FitProfile.FileId.MANUFACTURER, 2, 0x84)))
        w.data(0, listOf(FitProfile.FileId.TYPE_ACTIVITY, rng.nextInt(400).toLong()))
        w.define(1, FitProfile.MSG_SESSION, listOf(Triple(FitProfile.Session.SPORT, 1, 0x00)))
        w.data(1, listOf(listOf(2L, 2L, 2L, 0L, 1L, 5L, 11L, 17L, 254L, 255L)[rng.nextInt(10)]))
        val tsType = if (rng.nextInt(5) == 0) TYPES[rng.nextInt(TYPES.size)] else (0x86 to 4)
        val fields = ArrayList<Triple<Int, Int, Int>>()
        fields.add(Triple(FitProfile.FIELD_TIMESTAMP, tsType.second, tsType.first))
        for (f in RECORD_FIELDS) if (rng.nextBoolean()) {
            val (type, size) = TYPES[rng.nextInt(TYPES.size)]
            fields.add(Triple(f, size, type))
        }
        w.define(2, FitProfile.MSG_RECORD, fields)
        var t = 1_000_000_000L
        repeat(1 + rng.nextInt(300)) {
            t += when (rng.nextInt(50)) {
                0 -> rng.nextInt(100_000_000).toLong() // 큰 점프
                1 -> -rng.nextInt(10).toLong() // 거꾸로
                else -> 1L
            }
            val ts = if (tsType.first == 0x86) t else value(tsType.first, tsType.second, rng)
            w.data(2, listOf(ts) + fields.drop(1).map { value(it.third, it.second, rng) })
        }
        return w.build()
    }
}
