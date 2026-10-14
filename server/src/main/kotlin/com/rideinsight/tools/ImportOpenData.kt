package com.rideinsight.tools

// [파일 역할] GoldenCheetah OpenData 선수 1명을 DB에 넣는 명령줄 도구(Gradle importOpenData 태스크)다.
// 짝지어진 파워 라이딩은 활동·샘플·지표로, FTP 라벨(cp 또는 ftp)의 변화는 날짜별 FTP 이력으로 넣는다.
// [왜 필요한가] 실제 선수의 긴 기록으로 화면(훈련 부하, 기간 파워 커브, FTP 추정)을 시연·확인하기
// 위해서다(docs/api.md OpenData 가져오기). 2026-10-10 선수 1명(활동 516, FTP 이력 16개)을 가져와 화면을
// 확인했다(docs/ai-verification.md 2단계).
// [설계 이유] 서버와 같은 저장소·지표 서비스 빈을 재사용하려고 Spring 컨텍스트를 웹 서버 없이 띄운다.
// 라이딩마다 TransactionTemplate으로 활동·샘플·지표를 한 트랜잭션에 묶어, 중간에 실패해도 반쯤 저장된 활동이
// 남지 않게 한다(Codex 2차 검토 지적 3).
// [연결] 입력 라이딩 인덱스 JSON은 ml/의 rides.py가 만든다(라이딩 ↔ CSV 짝짓기, gc 메트릭, power_flag). CSV는
// OpenDataCsv로 zip에서 바로 읽는다.

import com.rideinsight.metrics.OpenDataCsv
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.service.MetricsService
import com.rideinsight.storage.ActivityRepository
import com.rideinsight.storage.AthleteRepository
import com.rideinsight.storage.DuplicateActivityException
import com.rideinsight.storage.SampleSeries
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipFile
import org.springframework.boot.runApplication
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// 명령줄 진입점: 옵션을 읽고(--zip, --index, --name 필수, --label 기본 cp, --limit 선택), 웹 서버 없는 Spring
// 컨텍스트에서 빈을 꺼내 importAthlete를 부른 뒤 결과 수를 출력한다.
/**
 * GoldenCheetah OpenData 선수 1명을 DB로 가져온다. CSV와 짝지어진 파워 라이딩은 활동이 되고, 고른 FTP
 * 라벨이 바뀌는 지점은 적용일이 붙은 FTP 이력이 된다.
 *
 * 사용법: ./gradlew -q importOpenData --args="--zip <선수.zip> --index <라이딩 인덱스 json> --name <선수 이름> [--label cp|ftp] [--limit N]"
 * DB 설정은 application.properties / RIDE_INSIGHT_DB_* 환경 변수에서 온다.
 */
fun main(args: Array<String>) {
    val options = parseOptions(args, required = listOf("--zip", "--index", "--name"))
    // --spring.main.web-application-type=none: 톰캣을 띄우지 않고 DB 연결·Flyway·빈만 준비한다. DB 접속
    // 정보는 application.properties(RIDE_INSIGHT_DB_* 환경 변수)에서 온다.
    val context = runApplication<com.rideinsight.ServerApplication>("--spring.main.web-application-type=none") {
        setLogStartupInfo(false)
    }
    try {
        val athletes = context.getBean(AthleteRepository::class.java)
        val activities = context.getBean(ActivityRepository::class.java)
        val metrics = context.getBean(MetricsService::class.java)
        // 서비스 밖에서 트랜잭션 경계를 직접 잡으려고 PlatformTransactionManager로 TransactionTemplate을
        // 만든다.
        val transactions = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
        val result = importAthlete(
            File(options.getValue("--zip")),
            File(options.getValue("--index")),
            options.getValue("--name"),
            options["--label"] ?: "cp",
            options["--limit"]?.toInt(),
            athletes,
            activities,
            metrics,
            transactions,
        )
        println("athlete=${result.athleteId} activities=${result.activities} skipped=${result.skipped} thresholds=${result.thresholds}")
    } finally {
        // 성공·실패와 상관없이 컨텍스트(커넥션 풀 등)를 닫는다.
        context.close()
    }
}

// 결과 요약: 만든 선수 id, 저장한 활동 수, 건너뛴 라이딩 수(zip에 CSV 없음, 샘플 2개 미만, 같은 시작 시각),
// 넣은 FTP 이력 수.
class ImportResult(val athleteId: UUID, val activities: Int, val skipped: Int, val thresholds: Int)

// 인덱스의 date는 'yyyy/MM/dd HH:mm:ss UTC' 형식이라 ' UTC'를 떼고 이 형식으로 읽는다(메타데이터 date는 UTC,
// README 데이터 절).
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

// 가져오기 본체. 입력: 선수 zip, 라이딩 인덱스 JSON, 선수 이름, 라벨 종류, 최대 저장 활동 수, 저장소·지표
// 서비스·트랜잭션 템플릿. 선수는 매번 새로 만든다.
fun importAthlete(
    zip: File,
    index: File,
    name: String,
    label: String,
    limit: Int?,
    athletes: AthleteRepository,
    activities: ActivityRepository,
    metrics: MetricsService,
    transactions: TransactionTemplate,
): ImportResult {
    require(label == "cp" || label == "ftp") { "--label must be cp or ftp" }
    // 인덱스 JSON의 성별(M/F일 때만)과 출생 연도로 선수를 만든다. 체중은 넣지 않는다.
    val tree = JsonMapper.builder().build().readTree(index)
    val athlete = athletes.insert(
        name,
        tree.path("gender").asString(null)?.takeIf { it == "M" || it == "F" },
        tree.path("yob").asString(null)?.toIntOrNull(),
        null,
    )
    // CSV와 짝지어졌고 파워 플래그가 있는 라이딩만, 날짜순으로.
    val rides = tree.path("rides").filter { it.path("csv").asString(null) != null && it.path("power_flag").asBoolean(false) }
        .sortedBy { it.path("date").asString() }

    // FTP 이력을 활동보다 먼저 넣어야 각 라이딩의 TSS를 그 날짜에 유효한 FTP로 계산할 수 있다. 라벨 값이
    // 직전과 달라질 때만 그 라이딩 날짜를 적용일로 한 줄 넣는다(출처 import).
    var thresholds = 0
    var last: Int? = null
    for (ride in rides) {
        val value = labelOf(ride, label) ?: continue
        if (value != last) {
            val date = LocalDateTime.parse(ride.path("date").asString().removeSuffix(" UTC"), DATE_FORMAT).toLocalDate()
            athletes.setThreshold(athlete.id, date, value, "import")
            thresholds++
            last = value
        }
    }

    var stored = 0
    var skipped = 0
    // zip을 풀지 않고 항목을 바로 스트림으로 읽는다. --limit이 있으면 그만큼 저장하고 멈춘다.
    ZipFile(zip).use { z ->
        for (ride in rides) {
            if (limit != null && stored >= limit) break
            val csv = ride.path("csv").asString()
            // 인덱스에 적힌 CSV가 zip에 없으면 건너뛴 수로 센다. 샘플이 2개 미만이면 기록 간격을 추정할 수
            // 없어 역시 건너뛴다.
            val entry = z.getEntry(csv) ?: run { skipped++; null } ?: continue
            val parsed = z.getInputStream(entry).bufferedReader().use { OpenDataCsv.read(it) }
            val samples = parsed.samples
            if (samples.size < 2) { skipped++; continue }
            val interval = RecordingInterval.analyse(samples.secs)
            // 메타데이터 date(UTC)를 활동 시작 시각으로 쓴다.
            val startedAt = LocalDateTime.parse(ride.path("date").asString().removeSuffix(" UTC"), DATE_FORMAT).atOffset(ZoneOffset.UTC)
            // 저장용 시리즈. 파워는 결측을 null로 보존한 powerNullable을 쓴다(Codex 2차 검토 지적 8: 처음에는
            // 결측을 0 W로 저장했다).
            val series = SampleSeries(
                secs = samples.secs.toList(),
                power = parsed.extras.powerNullable, // 비어 있는 칸은 저장소에서도 NULL로 남는다
                heartRate = parsed.extras.heartRate,
                cadence = parsed.extras.cadence,
                distanceM = parsed.extras.distanceM,
                altitudeM = parsed.extras.altitudeM,
            )
            // 라이딩 하나 = 트랜잭션 하나: 활동·샘플 insert와 지표 계산·저장이 함께 커밋되거나 함께 롤백된다.
            // 이 선수에게 같은 시작 시각의 활동이 이미 있으면 건너뛴다.
            val id = try {
                transactions.execute { activities.insert(
                    ActivityRepository.NewActivity(
                        athleteId = athlete.id,
                        startedAt = startedAt,
                        source = "opendata",
                        sourceName = csv,
                        sport = "cycling",
                        recIntSecs = interval?.intervalSecs,
                        intervalShare = interval?.modeShare,
                        sampleCount = samples.size,
                        durationSecs = samples.secs.last() + (interval?.intervalSecs ?: 0.0),
                        manufacturer = null,
                        product = null,
                        crcValid = null,
                        hasPower = true,
                        powerMissing = samples.powerMissing,
                    ),
                    series,
                ).also { newId -> metrics.computeAndStore(newId, athlete.id, startedAt, series, true) } }!!
            } catch (e: DuplicateActivityException) {
                skipped++
                continue
            }
            stored++
        }
    }
    return ImportResult(athlete.id, stored, skipped, thresholds)
}

/**
 * CP 라벨 = cp_setting, FTP 라벨 = GoldenCheetah가 IF 계산에 실제로 쓴 임계값(NP / IF, 즉 coggan_np /
 * coggan_if를 반올림). 라벨 정의는 3단계 규칙과 같다. 1단계에서 둘이 다른 라이딩이 10~13% 있었다
 * (docs/experiments.md). 값이 없거나 0 이하면 null.
 */
internal fun labelOf(ride: JsonNode, label: String): Int? {
    val gc = ride.path("gc")
    return if (label == "cp") {
        gc.path("cp_setting").asDouble(0.0).takeIf { it > 0 }?.let { Math.round(it).toInt() }
    } else {
        val np = gc.path("coggan_np").asDouble(0.0)
        val intensity = gc.path("coggan_if").asDouble(0.0)
        if (np > 0 && intensity > 0) Math.round(np / intensity).toInt() else null
    }
}

// '--키 값' 쌍만 받는 단순한 옵션 해석기. 짝이 맞지 않거나 필수 옵션이 없으면 IllegalArgumentException.
internal fun parseOptions(args: Array<String>, required: List<String>): Map<String, String> {
    val result = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(key.startsWith("--") && i + 1 < args.size) { "expected --option value, got '$key'" }
        result[key] = args[i + 1]
        i += 2
    }
    for (r in required) require(r in result) { "missing $r" }
    return result
}
