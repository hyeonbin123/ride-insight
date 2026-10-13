package com.rideinsight.service

// [파일 역할] FTP 추정 API 뒤의 서비스. 최근 weeks주의 기간 파워 커브로 규칙 R1·R2·R3를 계산하고, 서비스
// 추정값으로는 R1을 돌려준다.
// [설계 이유] 3단계 측정에서 채택 후보 M2(Ridge)와 R1의 MAE 차이가 −6.3 W [−9.1, −3.0]이라 미리 정한 '5 W
// 이상 개선'을 확정하지 못했다(H5 판정 불가). 규칙대로 R1을 쓰고, 측정한 오차를 함께 보여
// 준다(docs/experiments.md 3단계 서비스 반영, README).
// [연결] AthleteController의 GET /api/athletes/{id}/ftp-estimate가 부르고, 화면 대시보드의 FTP 추정 카드가
// 결과와 note를 보여 준다.

import com.rideinsight.metrics.FtpRules
import java.time.LocalDate
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * FTP estimate for an athlete from the last [weeks] weeks. The served estimator is chosen by the
 * stage-3 measurement (docs/experiments.md): until a model is adopted, the estimate is R1
 * (95 % of the best 20-minute power) and the other rules are returned as context.
 */
@Service
class FtpEstimateService(private val athletes: AthleteService) {
    companion object {
        // 3단계에서 잰 R1의 오차(reports/ftp/dev.md, test.md): dev MAE 31 W, 봉인 test MAE 29 W, 편향 약 −17
        // W(과소 추정). 등록한 서비스 규칙은 dev MAE를 보여 주라고 했고 test 값은
        // 덧붙였다(docs/experiments.md 규칙 변경 7). note 문장에 들어간다.
        /** Stage-3 numbers for rule R1 (reports/ftp/dev.md and test.md, 2026-10-10). The registered
         * serving rule asks for the dev MAE; the sealed test figure is given as well. */
        const val DEV_MAE_WATTS = 31
        const val SEALED_TEST_MAE_WATTS = 29
        const val SEALED_TEST_BIAS_WATTS = 17
    }

    // 추정 결과. 필드 뜻은 api/Dtos.kt의 FtpEstimateResponse와 같다.
    class Estimate(
        val method: String,
        val ftpWatts: Double?,
        val rules: Map<String, Double?>,
        val criticalPowerWPrimeKj: Double?,
        val windowsUsed: Int,
        val ridesInWindow: Int,
        val best20m: Double?,
        val best60m: Double?,
        val note: String,
    )

    // 입력: 선수 id, 기간(주), 기준일. 기간 커브(구간 초 → 최대 W)를 맵으로 바꿔 세 규칙에 넣는다. 없는 선수
    // 404와 weeks 범위(1~104) 검사는 먼저 불리는 AthleteService.powerCurve에서 난다.
    fun estimate(athleteId: UUID, weeks: Int, asOf: LocalDate): Estimate {
        val points = athletes.powerCurve(athleteId, weeks, asOf)
        val rides = athletes.powerRideCount(athleteId, weeks, asOf)
        val best = points.toMap()
        val r1 = FtpRules.twentyMinute(best)
        val r2 = FtpRules.sixtyMinute(best)
        val cp = FtpRules.criticalPower(best)
        return Estimate(
            method = "R1",
            ftpWatts = r1,
            // linkedMapOf라 응답에서도 R1·R2·R3 순서가 유지된다. W'는 J를 kJ로 바꿔 준다.
            rules = linkedMapOf("R1_20min_x0.95" to r1, "R2_60min" to r2, "R3_critical_power" to cp?.first),
            criticalPowerWPrimeKj = cp?.second?.div(1000.0),
            windowsUsed = best.size,
            ridesInWindow = rides,
            best20m = best[1200],
            best60m = best[3600],
            // 안내 문장. 20분 기록이 없으면 추정할 수 없는 이유를, 있으면 R1 정의·창 안 파워 라이딩 수·dev와
            // test 오차·편향·모델을 채택하지 않았다는 사실을 적는다.
            note = if (r1 == null) {
                "no ride of 20 minutes or more with power in the last $weeks weeks"
            } else {
                "rule R1 = 0.95 × best 20-minute power over $rides rides with power in the last $weeks weeks. " +
                    "Against athletes' own FTP settings R1 was off by $DEV_MAE_WATTS W on average (dev set) and " +
                    "$SEALED_TEST_MAE_WATTS W on the sealed test set, running about $SEALED_TEST_BIAS_WATTS W low; " +
                    "a learned model was not adopted (docs/experiments.md stage 3)"
            },
        )
    }
}
