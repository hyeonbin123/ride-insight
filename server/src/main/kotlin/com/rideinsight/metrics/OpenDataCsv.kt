package com.rideinsight.metrics

// [파일 역할] GoldenCheetah OpenData의 라이딩 CSV(`secs,km,power,hr,cad,alt`)를 읽어, 지표 계산용
// RideSamples와 DB 저장용 부가 시리즈로 바꾼다.
// [설계 이유] 깨진 행·중복 시각·결측 파워를 다루는 규칙은 1단계 측정 규칙에 미리 적은
// 그대로다(docs/experiments.md 1단계 데이터의 CSV 읽기). NaN·Infinity 처리와 행 수 상한은 Codex 1차 검토(지적
// 2·8)로, 저장용 결측 null 보존과 smallint 범위 처리는 Codex 2차 검토(지적 3·8)로 추가했다.
// [연결] ParityTool(1단계 GC 대조, 3단계 특징 내보내기)과 ImportOpenData(DB 가져오기)가 zip 안의 CSV를 풀지
// 않고 스트림으로 이 함수에 넘긴다.

import java.io.BufferedReader

/**
 * GoldenCheetah OpenData 라이딩 CSV 파일(`secs,km,power,hr,cad,alt`)을 읽는다.
 *
 * - 비었거나 유한하지 않은 파워 칸은 0.0이 되고 [RideSamples.powerMissing]에 세어진다
 * - `secs`가 없거나 유한하지 않거나 직전 행보다 작은 행은 건너뛰고 [Result.badRows]에 센다
 * - `secs`가 직전 행과 같은 행은 그대로 둔다(GoldenCheetah도 남긴다). 호출하는 쪽이 보고할 수 있도록
 *   [Result.duplicateRows]에 센다
 * - 행이 [maxRows]개를 넘으면 거절해, 악의적이거나 손상된 입력에도 메모리 사용이 한정되게 한다
 * - 나머지 시리즈(심박, 케이던스, 거리, 고도)는 저장용으로 [Result.extras]에 nullable 리스트로 남긴다.
 *   파워 지표에는 영향을 주지 않는다
 */
object OpenDataCsv {
    // 행 수 상한 100만. 1초 기록이면 약 278시간 분량이라 정상 라이딩은 넉넉히 들어가고, 손상되거나 조작된
    // 파일이 메모리를 끝없이 쓰지 못하게 한다(Codex 1차 검토 지적 8).
    const val DEFAULT_MAX_ROWS = 1_000_000

    /**
     * DB smallint 컬럼(최대 32,767)에 넣을 값의 상한. 넘는 값은 센서가 낸 쓰레기 값으로 보고 저장할 때 null로
     * 둔다(Codex 2차 검토 지적 3).
     */
    const val SMALLINT_MAX = 32_000

    // 저장용 시리즈. 지표 입력(RideSamples.power)은 결측을 0으로 채우지만, 저장할 파워(powerNullable)는
    // 결측을 null로 남긴다(Codex 2차 검토 지적 8: 처음에는 결측 파워를 0 W로 저장했다). 다른 시리즈는 값이
    // 하나도 없으면 null이다.
    class Extras(
        /** 빈 칸을 null로 둔 파워(지표 입력인 [RideSamples]는 대신 0을 쓴다). */
        val powerNullable: List<Int?>,
        val heartRate: List<Int?>?,
        val cadence: List<Int?>?,
        val distanceM: List<Double?>?,
        val altitudeM: List<Double?>?,
    )

    // 읽은 결과: 지표 입력, 깨진 행 수, 중복 시각 행 수, power 열 유무, 저장용 시리즈. 행 수들은 1단계 보고
    // 항목이다.
    class Result(
        val samples: RideSamples,
        val badRows: Int,
        val duplicateRows: Int,
        val hasPowerColumn: Boolean,
        val extras: Extras,
    )

    // 한 줄씩 읽는 스트리밍 방식이라 zip 항목의 입력 스트림을 그대로 받을 수 있다. 첫 줄은 헤더이고, 빈
    // 파일이면 빈 라이딩을 돌려준다.
    fun read(reader: BufferedReader, maxRows: Int = DEFAULT_MAX_ROWS): Result {
        val header = reader.readLine()
            ?: return Result(RideSamples(DoubleArray(0), DoubleArray(0)), 0, 0, false, Extras(emptyList(), null, null, null, null))
        // 열 순서에 기대지 않고 이름으로 위치를 찾는다. 없는 열은 위치 -1이라 값이 null로 읽힌다. 다만 secs
        // 열이 없으면 시간축을 만들 수 없어 거절한다.
        val columns = header.split(',').map { it.trim() }
        val secsIndex = columns.indexOf("secs")
        val powerIndex = columns.indexOf("power")
        val hrIndex = columns.indexOf("hr")
        val cadIndex = columns.indexOf("cad")
        val kmIndex = columns.indexOf("km")
        val altIndex = columns.indexOf("alt")
        require(secsIndex >= 0) { "CSV has no 'secs' column: $header" }

        val secs = ArrayList<Double>()
        val power = ArrayList<Double>()
        val powerNullable = ArrayList<Int?>()
        val hr = ArrayList<Int?>()
        val cad = ArrayList<Int?>()
        val dist = ArrayList<Double?>()
        val alt = ArrayList<Double?>()
        var missing = 0
        var bad = 0
        var duplicates = 0
        // 직전에 받아들인 행의 secs. 시간이 거꾸로 가는 행을 가려내는 데 쓴다.
        var lastSecs = Double.NEGATIVE_INFINITY
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cells = line.split(',')
            // 칸 하나를 숫자로 읽는다. 열이 없거나, 칸이 비었거나, 숫자가 아니거나, NaN·Infinity면 null.
            // toDoubleOrNull은 "NaN"·"Infinity" 문자열도 숫자로 받아들이므로 isFinite로 한 번 더 거른다(Codex
            // 1차 검토 지적 2: NaN이 NP까지 퍼질 수 있었다).
            fun num(index: Int): Double? =
                if (index >= 0) cells.getOrNull(index)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } else null
            val s = num(secsIndex)
            // secs가 없거나 직전 행보다 작으면 깨진 행으로 세고 버린다. 같은 값(중복 시각)은 GC처럼 그대로
            // 쓰되 수를 센다.
            if (s == null || s < lastSecs) {
                bad++
                continue
            }
            if (s == lastSecs && secs.isNotEmpty()) duplicates++
            lastSecs = s
            // 파워가 없으면 지표 입력에는 0 W로 넣고(GC가 빈 값을 0으로 두는 방식) 결측으로 센다.
            val p = num(powerIndex)
            if (p == null) missing++
            secs.add(s)
            power.add(p ?: 0.0)
            // 저장용 정수 시리즈: 반올림한 뒤 0~32,000 밖이면 null. 거리는 km를 m로 바꾼다.
            powerNullable.add(p?.let { Math.round(it).toInt() }?.takeIf { it in 0..SMALLINT_MAX })
            hr.add(num(hrIndex)?.let { Math.round(it).toInt() }?.takeIf { it in 0..SMALLINT_MAX })
            cad.add(num(cadIndex)?.let { Math.round(it).toInt() }?.takeIf { it in 0..SMALLINT_MAX })
            dist.add(num(kmIndex)?.let { it * 1000.0 })
            alt.add(num(altIndex))
            // 행 수가 상한을 넘는 순간 IllegalArgumentException으로 중단한다.
            require(secs.size <= maxRows) { "CSV has more than $maxRows rows" }
        }
        // 값이 하나도 없는 시리즈는 null로 바꿔 열이 없는 것과 똑같이 다룬다.
        fun <T> some(list: List<T?>): List<T?>? = list.takeIf { l -> l.any { it != null } }
        return Result(
            RideSamples(secs.toDoubleArray(), power.toDoubleArray(), missing),
            bad,
            duplicates,
            powerIndex >= 0,
            Extras(powerNullable, some(hr), some(cad), some(dist), some(alt)),
        )
    }
}
