package com.rideinsight.api

// [파일 역할] 활동(라이딩) 하나를 id로 다루는 REST 엔드포인트 묶음이다: 상세, 시계열 샘플, 활동 파워 커브,
// 삭제. 경로는 모두 /api/activities/{id} 아래다(docs/api.md API 표).
// [설계 이유] 선수에 딸린 동작(업로드·목록·훈련 부하)은 AthleteController에, 활동 id만으로 접근하는 동작은
// 여기에 둬서 URL 구조와 컨트롤러를 맞췄다. 컨트롤러는 입력 검사와 응답 변환만 하고 조회·계산은
// ActivityService가 한다.
// [연결] 화면의 web/src/api/client.ts(getActivity, getSamples, getActivityPowerCurve, deleteActivity)가
// 호출한다. 예외는 ApiExceptionHandler가 상태 코드로 바꾼다(없는 id → 404, 잘못된 every·UUID → 400).

import com.rideinsight.service.ActivityService
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// 반환값을 JSON 본문으로 쓰는 컨트롤러. ActivityService는 생성자 주입으로 받는다.
@RestController
@RequestMapping("/api/activities")
class ActivityController(private val activities: ActivityService) {
    companion object {
        // samples 응답 하나에 담는 최대 점 수(docs/api.md: 응답이 20,000점을 넘지 않도록 N을 자동으로
        // 키운다). 몇 시간짜리 1초 기록을 그대로 보내지 않게 하는 상한이다.
        const val MAX_POINTS = 20_000
    }

    // GET /api/activities/{id}: 활동 요약과 저장된 지표. 경로의 id가 UUID 형식이 아니면 Spring이
    // MethodArgumentTypeMismatchException을 내고 400이 된다.
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ActivityResponse = ActivityResponse.from(activities.get(id))

    // GET /api/activities/{id}/samples?every=N: 시계열을 경과 시간 N초 구간마다 첫 샘플 하나씩 골라
    // 돌려준다(화면의 파워·심박 그래프 입력). 처음 구현은 N번째 샘플마다였는데 Codex 2차 검토(지적 6)로 초
    // 단위로 바꿨다. 실제 고르기는 SamplesResponse.from(Dtos.kt)이 한다.
    @GetMapping("/{id}/samples")
    fun samples(@PathVariable id: UUID, @RequestParam(defaultValue = "1") every: Int): SamplesResponse {
        // every가 0 이하면 구간 번호 계산(t / every)이 성립하지 않으므로 IllegalArgumentException → 400으로
        // 거절한다(ApiIntegrationTest가 every=0을 확인).
        require(every >= 1) { "'every' must be at least 1" }
        val series = activities.samples(id)
        // Never return more than MAX_POINTS: widen the bucket (seconds) if the caller asked for too much.
        // 구간 번호는 floor(t / every)라, 길이(마지막 − 첫 경과 시간, 초)가 span이면 구간은 최대
        // ceil(span / every) + 1개다(양 끝이 모두 구간의 시작에 걸리는 경우). 그래서 span을 MAX_POINTS − 1로 나눠
        // 올림한 값이 상한을 지키는 최소 구간 폭이다. 처음에는 MAX_POINTS로 나눠, 1초 기록 20,000초짜리
        // 라이딩(샘플 20,001개)에 every=1을 그대로 써서 20,001점을 돌려줬다(2026-10-13 수정). 요청한 every와 이
        // 값 중 큰 쪽을 쓰고, 실제로 쓴 값은 응답의 every로 알려 준다. 샘플이 없으면 길이를 0으로 본다.
        val spanSecs = (series.secs.lastOrNull() ?: 0.0) - (series.secs.firstOrNull() ?: 0.0)
        val minEvery = Math.ceil(spanSecs / (MAX_POINTS - 1)).toInt()
        return SamplesResponse.from(series, maxOf(every, minEvery, 1))
    }

    // GET /api/activities/{id}/power-curve: 활동 하나의 전 구간 MMP 곡선(로그 간격, 300점 이하)과 저장된
    // 16구간 표준 MMP를 함께 돌려준다. 곡선은 ActivityService.powerCurve가 저장된 샘플로 다시 계산하고, (초,
    // 와트) 쌍을 CurvePoint로 바꿔 응답한다.
    @GetMapping("/{id}/power-curve")
    fun powerCurve(@PathVariable id: UUID): PowerCurveResponse {
        val (points, standard) = activities.powerCurve(id)
        return PowerCurveResponse(points.map { (s, w) -> CurvePoint(s, w) }, standard)
    }

    // DELETE /api/activities/{id}: 활동을 지운다. 샘플·지표 행은 DB의 on delete cascade로 함께
    // 지워진다(V1__init.sql). 성공하면 본문 없는 204, 없는 id면 404.
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        activities.delete(id)
        return ResponseEntity.noContent().build()
    }
}
