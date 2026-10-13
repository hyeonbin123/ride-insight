package com.rideinsight.api

// [파일 역할] 선수 단위 REST 엔드포인트 묶음이다: 선수 생성·조회, FTP 이력과 변경, FIT 업로드, 활동 목록,
// 훈련 부하(PMC), FTP 추정, 기간 파워 커브. 경로는 모두 /api/athletes 아래다(docs/api.md API 표).
// [설계 이유] 컨트롤러는 요청 검사(@Valid), 기본값 채우기, DTO 변환만 하고 계산·저장은 서비스(AthleteService,
// ActivityService, FtpEstimateService)에 맡긴다. 인증은 없다(README 한계: 로컬 데모라 선수 id를 아는 사람은
// 누구나 읽고 쓸 수 있다. 5단계에서 넣지 않기로 미리 정함).
// [연결] 화면의 web/src/api/client.ts가 호출하고, ApiIntegrationTest가 api.md 완료 기준대로 확인한다. 날짜
// 기본값은 모두 UTC 기준 오늘이다.

import com.rideinsight.service.ActivityService
import com.rideinsight.service.AthleteService
import com.rideinsight.service.FtpEstimateService
import com.rideinsight.service.InvalidActivityException
import jakarta.validation.Valid
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

// 세 서비스를 생성자 주입으로 받는다. ftp는 FTP 추정 전용 서비스다.
@RestController
@RequestMapping("/api/athletes")
class AthleteController(
    private val athletes: AthleteService,
    private val activities: ActivityService,
    private val ftp: FtpEstimateService,
) {
    // POST /api/athletes: 선수 생성. 본문 검사(이름 1~100자, 성별 M/F, 출생 연도 1900~2100, 체중 20 kg 초과
    // 300 kg 미만, FTP 50~1000 W)는 CreateAthleteRequest의 애너테이션이 하고 실패하면 400이다. 이름 앞뒤 공백은
    // 잘라 저장하고, 201 Created와 Location 헤더(/api/athletes/{id})를 돌려준다. 체중은 real 컬럼과 같은 Float으로
    // 받아 검사했으므로 Double로 바꿔도 값이 그대로다.
    @PostMapping
    fun create(@Valid @RequestBody req: CreateAthleteRequest): ResponseEntity<AthleteResponse> {
        val (athlete, threshold) = athletes.create(req.name.trim(), req.gender, req.birthYear, req.weightKg?.toDouble(), req.ftp)
        return ResponseEntity.created(URI.create("/api/athletes/${athlete.id}")).body(AthleteResponse.from(athlete, threshold))
    }

    // GET /api/athletes/{id}: 선수 정보와 오늘(UTC) 기준으로 유효한 FTP. 없는 id는 404.
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): AthleteResponse {
        val (athlete, threshold) = athletes.get(id)
        return AthleteResponse.from(athlete, threshold)
    }

    // GET /api/athletes/{id}/thresholds: 날짜별 FTP 이력 전체(적용일 오름차순).
    @GetMapping("/{id}/thresholds")
    fun thresholds(@PathVariable id: UUID): List<ThresholdResponse> = athletes.thresholds(id).map(ThresholdResponse::from)

    // PUT /api/athletes/{id}/threshold: effectiveFrom 날짜부터 적용할 FTP를 등록하고(같은 날짜가 있으면
    // 덮어씀), 그 날짜 이후 활동의 IF·TSS를 다시 계산한 개수를 recomputedActivities로 돌려준다(api.md: 그
    // 날짜 이후 활동의 TSS를 다시 계산한다).
    @PutMapping("/{id}/threshold")
    fun setThreshold(@PathVariable id: UUID, @Valid @RequestBody req: ThresholdRequest): Map<String, Any> {
        val recomputed = athletes.setThreshold(id, req.effectiveFrom, req.ftpWatts)
        return mapOf("effectiveFrom" to req.effectiveFrom, "ftpWatts" to req.ftpWatts, "recomputedActivities" to recomputed)
    }

    // POST /api/athletes/{id}/activities: multipart의 file 파트로 FIT 파일 하나를 받아 저장하고 지표를
    // 계산한다(201 + 활동 요약). consumes를 multipart/form-data로 제한해 다른 Content-Type은 415가 된다. 10
    // MB 초과는 컨트롤러에 들어오기 전 multipart 해석 단계에서 413으로 막힌다.
    @PostMapping("/{id}/activities", consumes = ["multipart/form-data"])
    fun upload(@PathVariable id: UUID, @RequestPart("file") file: MultipartFile): ResponseEntity<ActivityResponse> {
        // 빈 파일은 디코더까지 보내지 않고 400 invalid_activity로 바로 거절한다.
        if (file.isEmpty) throw InvalidActivityException("the uploaded file is empty")
        // 업로드 상한이 10 MB라 파일 전체를 바이트 배열로 읽어 디코더에 넘긴다. 원래 파일 이름은 출처
        // 표시용으로 저장된다(200자까지).
        val stored = activities.ingestFit(id, file.bytes, file.originalFilename)
        return ResponseEntity.created(URI.create("/api/activities/${stored.activity.id}")).body(ActivityResponse.from(stored))
    }

    // GET /api/athletes/{id}/activities?before&limit: 최신순 활동 목록. before(시작 시각)보다 이전 것만 주는
    // 커서 방식이다(처음 문서의 from&to&cursor는 Codex 2차 검토로 구현에 맞춰 고침). limit 기본 50,
    // 서비스에서 1~200으로 잘린다.
    @GetMapping("/{id}/activities")
    fun list(
        @PathVariable id: UUID,
        @RequestParam(required = false) before: OffsetDateTime?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ActivityListResponse {
        val items = activities.list(id, before, limit).map(ActivityResponse::from)
        // 한 페이지를 꽉 채웠으면 마지막(가장 오래된) 항목의 시작 시각을 nextBefore로 준다. 클라이언트는 이를
        // 다음 요청의 before로 넣는다. 같은 선수의 시작 시각은 유일(unique 제약)이라 시각만으로 커서를 잡아도
        // 항목이 겹치거나 빠지지 않는다. 덜 채웠으면 마지막 페이지라 null(응답에서 생략).
        val next = if (items.size >= limit.coerceIn(1, 200)) items.last().startedAt else null
        return ActivityListResponse(items, next)
    }

    // GET /api/athletes/{id}/training-load?from&to: 날짜별 TSS 합과 CTL·ATL·TSB(PMC). to가 없으면 오늘,
    // from이 없으면 to의 90일 전. 범위 검사(to ≥ from, 약 3년 이하)와 첫 활동부터의 워밍업은
    // AthleteService.trainingLoad가 한다. 화면의 대시보드가 쓴다.
    @GetMapping("/{id}/training-load")
    fun trainingLoad(
        @PathVariable id: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): TrainingLoadResponse {
        val end = to ?: LocalDate.now(ZoneOffset.UTC)
        val start = from ?: end.minusDays(90)
        return TrainingLoadResponse(
            athletes.trainingLoad(id, start, end).map { TrainingLoadDay(it.date, it.tss, it.ctl, it.atl, it.tsb) },
        )
    }

    // GET /api/athletes/{id}/ftp-estimate?weeks&asOf: asOf(기본 오늘)까지 최근 weeks주(기본 6주)의 기록으로
    // FTP를 추정한다. 3단계 측정에서 학습 모델이 채택되지 않아(H5 판정 불가) 규칙 R1(20분 최대 × 0.95)을
    // 돌려주고, R2·R3 값과 근거(창 안 파워 라이딩 수, 측정한 오차)를 함께 준다(docs/experiments.md 3단계
    // 서비스 반영).
    @GetMapping("/{id}/ftp-estimate")
    fun ftpEstimate(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "6") weeks: Int,
        @RequestParam(required = false) asOf: LocalDate?,
    ): FtpEstimateResponse {
        val e = ftp.estimate(id, weeks, asOf ?: LocalDate.now(ZoneOffset.UTC))
        return FtpEstimateResponse(
            e.method, e.ftpWatts, e.rules, e.criticalPowerWPrimeKj, e.windowsUsed, e.ridesInWindow, e.best20m, e.best60m, weeks, e.note,
        )
    }

    // GET /api/athletes/{id}/power-curve?weeks&asOf: 기간 안 활동들의 16구간 MMP 중 구간별 최대(기간 최대
    // 파워 커브). 3단계 FTP 추정 입력과 같은 값이다. 화면은 대시보드와 활동 상세의 6주 최고 비교 곡선에 쓴다.
    @GetMapping("/{id}/power-curve")
    fun powerCurve(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "6") weeks: Int,
        @RequestParam(required = false) asOf: LocalDate?,
    ): PowerCurveResponse {
        val points = athletes.powerCurve(id, weeks, asOf ?: LocalDate.now(ZoneOffset.UTC))
        return PowerCurveResponse(
            points.map { (s, w) -> CurvePoint(s, w) },
            // standard는 같은 값을 GoldenCheetah 메타데이터 이름(예: 20m_critical_power)으로 키를 바꾼
            // 것이다. 활동 파워 커브와 같은 응답 형태(PowerCurveResponse)를 쓰기 위해서다.
            points.associate { (s, w) -> com.rideinsight.metrics.MeanMaximalPower.metricKey(s) to w },
        )
    }
}
