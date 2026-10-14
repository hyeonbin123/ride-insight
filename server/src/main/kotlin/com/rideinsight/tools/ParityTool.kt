package com.rideinsight.tools

// [파일 역할] 1단계(지표 정확성) 측정용 오프라인 도구다. 지정한 선수들의 짝지어진 파워 라이딩마다 이 저장소의
// NP·IF·TSS·16구간 MMP를 계산해, GoldenCheetah 메타데이터 값과 나란히 한 줄짜리 JSON(JSONL)으로 쓴다.
// [설계 이유] 측정 대상이 서버의 Kotlin 구현 자체이므로 계산은 서버와 같은 metrics 패키지 함수로 하고, 통계
// 분석(부트스트랩 구간, 가설 판정)만 Python(ml/의 parity.py)에 맡겼다. 3단계 FTP 실험의 라이딩 특징(우리
// 구현의 NP·MMP, 체중)도 이 도구로 표본 전체를 내보냈다(docs/experiments.md 3단계 데이터).
// [연결] Gradle parity 태스크(build.gradle.kts)로 실행한다. 라이딩 인덱스는 ml/의 rides.py가 만들고, 선수
// 목록은 manifests/parity_dev.txt·parity_sealed.txt, 출력은 reports/parity/의 jsonl이다.

import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.OpenDataCsv
import com.rideinsight.metrics.PowerMetrics
import com.rideinsight.metrics.RecordingInterval
import java.io.File
import java.util.zip.ZipFile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// 진입점: 선수 목록 파일을 한 줄에 한 id로 읽고, 선수마다 인덱스 JSON과 zip을 열어 라이딩별 결과를 JSONL로
// 쓴다. 인덱스나 zip이 없는 선수는 표준 오류에 남기고 건너뛴다. 진행 상황은 표준 오류, 최종 요약은 표준
// 출력으로 낸다.
/**
 * docs/experiments.md 1단계용 오프라인 도구: 목록에 있는 선수들의 짝지어진 파워 라이딩마다 NP, IF, TSS와
 * 16구간 최대 평균 파워를 계산해, GoldenCheetah 메타데이터 값과 나란히 JSON 줄로 쓴다. 라이딩 인덱스는
 * `python -m ride_insight_ml.rides`가 만들고, 분석은 `python -m ride_insight_ml.parity`가 한다.
 *
 * 사용법: --zips <폴더> --index <폴더> --athletes <파일: 한 줄에 id 하나> --out <jsonl>
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    val zips = File(options.getValue("--zips"))
    val index = File(options.getValue("--index"))
    val athletes = File(options.getValue("--athletes")).readLines().map { it.trim() }.filter { it.isNotEmpty() }
    val out = File(options.getValue("--out"))
    out.parentFile?.mkdirs()

    val mapper = JsonMapper.builder().build()
    var rides = 0
    out.bufferedWriter().use { writer ->
        for (athlete in athletes) {
            val indexFile = File(index, "$athlete.json")
            val zipFile = File(zips, "$athlete.zip")
            if (!indexFile.exists() || !zipFile.exists()) {
                System.err.println("skip $athlete: missing index or zip")
                continue
            }
            val tree = mapper.readTree(indexFile)
            // zip을 풀지 않고 CSV 항목을 바로 스트림으로 읽는다. 짝지어진 CSV가 없거나 파워 플래그가 없거나
            // zip에 CSV가 없는 라이딩은 건너뛴다(1단계 라이딩 조건).
            ZipFile(zipFile).use { zip ->
                for (ride in tree.path("rides")) {
                    val csv = ride.path("csv").asString(null) ?: continue
                    if (!ride.path("power_flag").asBoolean(false)) continue
                    val entry = zip.getEntry(csv) ?: continue
                    val parsed = zip.getInputStream(entry).bufferedReader().use { OpenDataCsv.read(it) }
                    val line = compare(athlete, ride, csv, parsed)
                    writer.write(mapper.writeValueAsString(line))
                    writer.newLine()
                    rides++
                }
            }
            System.err.println("$athlete done ($rides rides so far)")
        }
    }
    println("rides=$rides out=${out.path}")
}

// 라이딩 하나의 비교 행을 만든다. 기록 간격은 CSV에서 추정한다(GC의 recIntSecs는 CSV에 없음). 이 도구의 TSS는
// cp_setting으로 계산하고, GC가 실제로 쓴 임계값(coggan_np / coggan_if)으로 다시 계산하는 일은 분석
// 쪽(parity.py)이 한다(규칙 변경 2).
internal fun compare(athlete: String, ride: JsonNode, csv: String, parsed: OpenDataCsv.Result): Map<String, Any?> {
    val samples = parsed.samples
    val interval = RecordingInterval.analyse(samples.secs)
    val recInt = interval?.intervalSecs
    val gc = ride.path("gc")
    val cp = gc.path("cp_setting").asDouble(0.0).takeIf { it > 0.0 }

    val np = recInt?.let { PowerMetrics.normalizedPower(samples.power, it) }
    val intensity = np?.let { PowerMetrics.intensityFactor(it, cp) }
    val tss = if (np != null && recInt != null) PowerMetrics.trainingStressScore(np, samples.size, recInt, cp) else null
    val mmp = LinkedHashMap<String, Double?>()
    for (window in MeanMaximalPower.STANDARD_WINDOWS_SECS) {
        mmp[MeanMaximalPower.metricKey(window)] =
            recInt?.let { MeanMaximalPower.peakPower(samples, it, window.toDouble()) }
    }
    // 보고 항목: 3000 W 이상 샘플 수, 음수 파워 샘플 수.
    val over3000 = samples.power.count { it >= 3000.0 }
    val negative = samples.power.count { it < 0.0 }

    return linkedMapOf(
        "athlete" to athlete,
        "date" to ride.path("date").asString(null),
        "csv" to csv,
        "rec_int" to recInt,
        "rec_int_share" to interval?.modeShare,
        "samples" to samples.size,
        "last_secs" to (samples.secs.lastOrNull()),
        "power_missing" to samples.powerMissing,
        "bad_rows" to parsed.badRows,
        "duplicate_rows" to parsed.duplicateRows,
        "over_3000" to over3000,
        "negative" to negative,
        "cp" to cp,
        // 체중은 3단계 특징(M1b의 체중당 특징)에 쓴다. 20~300 kg 밖이면 버린다.
        "weight" to gc.path("athlete_weight").nullableDouble()?.takeIf { it > 20.0 && it < 300.0 },
        "ours" to linkedMapOf("np" to np, "if" to intensity, "tss" to tss, "mmp" to mmp),
        // GC 메타데이터 값: NP, IF, TSS, 평균 파워, workout_time, 16구간 MMP. 없으면 null로 써서 분석 쪽이
        // '둘 다 없음'과 '한쪽만 있음'을 구분할 수 있게 한다(1단계 규칙).
        "gc" to linkedMapOf(
            "np" to gc.path("coggan_np").nullableDouble(),
            "if" to gc.path("coggan_if").nullableDouble(),
            "tss" to gc.path("coggan_tss").nullableDouble(),
            "average_power" to gc.path("average_power").nullableDouble(),
            "workout_time" to gc.path("workout_time").nullableDouble(),
            "mmp" to MeanMaximalPower.STANDARD_WINDOWS_SECS.associate { w ->
                val key = MeanMaximalPower.metricKey(w)
                key to gc.path(key).nullableDouble()
            },
        ),
    )
}

// JSON null이나 없는 키는 null, 그 외는 Double.
private fun JsonNode.nullableDouble(): Double? = if (isNull || isMissingNode) null else asDouble()

// '--키 값' 쌍 해석. 네 옵션(--zips, --index, --athletes, --out)이 모두 있어야 한다.
private fun parseArgs(args: Array<String>): Map<String, String> {
    val result = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(key.startsWith("--") && i + 1 < args.size) { "expected --option value, got '$key'" }
        result[key] = args[i + 1]
        i += 2
    }
    for (required in listOf("--zips", "--index", "--athletes", "--out")) {
        require(required in result) { "missing $required" }
    }
    return result
}
