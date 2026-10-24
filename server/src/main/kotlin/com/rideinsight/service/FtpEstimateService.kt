package com.rideinsight.service

// [파일 역할] FTP 추정 API 뒤의 서비스. 최근 weeks주의 기간 파워 커브로 규칙 R1·R2·R3를 계산하고, 서비스
// 추정값으로는 R1을 돌려준다. 함께 보여 주는 측정 오차는 3단계가 실제로 잰 조건(6주 창, 창 안 파워 라이딩 8개
// 이상) 안의 추정에만 붙인다.
// [설계 이유] 3단계 측정에서 채택 후보 M2(Ridge)와 R1의 MAE 차이가 −6.3 W [−9.1, −3.0]이라 미리 정한 '5 W
// 이상 개선'을 확정하지 못했다(H5 판정 불가). 규칙대로 R1을 쓰고, 측정한 오차를 함께 보여
// 준다(docs/experiments.md 3단계 서비스 반영, README). 다만 그 오차는 42일 창에서 창 안 파워 라이딩이 8개 이상인
// 예측 단위로만 잰 값이라, 대시보드의 12·52주 창이나 라이딩이 적은 창에 그대로 붙이면 잰 적 없는 추정기를
// 검증된 것처럼 보이게 된다. 그래서 조건 밖에서는 오차를 빼고 '재지 않았다'고만 적는다(2026-10-24,
// docs/experiments.md 규칙 변경 14. 기록만이고 측정 수치는 그대로다).
// [연결] AthleteController의 GET /api/athletes/{id}/ftp-estimate가 부르고, 화면 대시보드의 FTP 추정 카드가
// 결과와 note를 보여 준다. 오차 상수는 FtpEstimateServiceTest가 reports/ftp/dev.json·test.json과 대조한다.

import com.rideinsight.metrics.FtpRules
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * 최근 [weeks]주 기록으로 선수의 FTP를 추정한다. 서비스할 추정 방법은 3단계 측정(docs/experiments.md)으로
 * 정한다. 모델이 채택되기 전까지는 R1(20분 최대 파워의 95%)을 추정값으로 쓰고, 나머지 규칙은 참고용으로 함께
 * 돌려준다.
 */
@Service
class FtpEstimateService(private val athletes: AthleteService) {
    companion object {
        /**
         * 3단계(2026-10-10)에서 잰 규칙 R1의 오차(reports/ftp/dev.md, test.md): dev MAE 31 W, 봉인 test MAE
         * 29 W, 편향 약 −17 W(과소 추정). 등록한 서비스 규칙은 dev MAE를 보여 주라고 했고, 봉인 test 값도
         * 덧붙였다(docs/experiments.md 규칙 변경 7). note 문장에 들어간다. 값은 보고서 JSON의 R1 `mae.point`·
         * `bias.point`를 가장 가까운 정수로 반올림한 것이고(편향은 크기만 들고 부호는 컨트롤러가 뒤집어 돌려준다),
         * FtpEstimateServiceTest가 보고서와 같은지 확인한다. 보고서를 다시 만들면 여기도 함께 바꿔야 한다.
         */
        const val DEV_MAE_WATTS = 31
        const val SEALED_TEST_MAE_WATTS = 29
        const val SEALED_TEST_BIAS_WATTS = 17

        /**
         * 위 오차를 잰 조건(docs/experiments.md 3단계 데이터): 창 길이 6주 = 42일(`ml/src/ride_insight_ml/ftp.py`의
         * WINDOW_DAYS = 42), 창 안 파워 라이딩 8개 이상(같은 파일의 MIN_RIDES = 8. 8개 미만인 예측 단위는 측정에서
         * 뺐다). 창의 정렬은 조금 다르다: 측정은 기준 라이딩 시각 기준 직전 42일, 서비스는 asOf 다음 날 현지 0시
         * 기준 weeks주(AthleteService.powerRideCount). 길이가 같으므로 같은 조건으로 본다.
         */
        const val MEASURED_WINDOW_WEEKS = 6
        const val MEASURED_MIN_RIDES = 8

        /**
         * [weeks]주 창에 파워 라이딩 [ridesInWindow]개인 추정이 측정 조건 안인지. 조건 밖(12·52주 창, 라이딩 8개
         * 미만)의 오차는 잰 적이 없으므로 응답과 note에서 오차를 뺀다. 창 길이는 정확히 6주여야 한다(5·7주도 밖).
         * 순수 함수라 FtpEstimateServiceTest가 경계(5·6·7주, 7·8개)를 확인한다.
         */
        fun withinMeasuredRange(weeks: Int, ridesInWindow: Int): Boolean =
            weeks == MEASURED_WINDOW_WEEKS && ridesInWindow >= MEASURED_MIN_RIDES
    }

    // 추정 결과. 필드 뜻은 api/Dtos.kt의 FtpEstimateResponse와 같다. withinMeasuredRange는 위 withinMeasuredRange()의
    // 값으로, 컨트롤러가 오차 숫자를 응답에 넣을지 정하는 데 쓴다.
    class Estimate(
        val method: String,
        val ftpWatts: Double?,
        val rules: Map<String, Double?>,
        val criticalPowerWPrimeKj: Double?,
        val windowsUsed: Int,
        val ridesInWindow: Int,
        val best20m: Double?,
        val best60m: Double?,
        val withinMeasuredRange: Boolean,
        val note: String,
    )

    // 입력: 선수 id, 기간(주), 기준일. 기간 커브(구간 초 → 최대 W)를 맵으로 바꿔 세 규칙에 넣는다. 없는 선수
    // 404와 weeks 범위(1~104) 검사는 먼저 불리는 AthleteService.powerCurve에서 난다.
    fun estimate(athleteId: UUID, weeks: Int, asOf: LocalDate, zone: ZoneId): Estimate {
        val points = athletes.powerCurve(athleteId, weeks, asOf, zone)
        val rides = athletes.powerRideCount(athleteId, weeks, asOf, zone)
        val best = points.toMap()
        val r1 = FtpRules.twentyMinute(best)
        val r2 = FtpRules.sixtyMinute(best)
        val cp = FtpRules.criticalPower(best)
        val within = withinMeasuredRange(weeks, rides)
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
            withinMeasuredRange = within,
            // 안내 문장(영어, API 응답 그대로). 20분 기록이 없으면 추정할 수 없는 이유를 적는다. 있으면 R1 정의와 창 안
            // 파워 라이딩 수 뒤에, 측정 조건 안이면 dev와 test 오차·편향을, 밖이면 오차 대신 '이 창은 재지 않았다'를
            // 적고, 모델을 채택하지 않았다는 사실로 끝낸다. 조건 밖 문장에는 오차 수치를 넣지 않는다.
            note = if (r1 == null) {
                "no ride of 20 minutes or more with power in the last $weeks weeks"
            } else {
                val rule = "rule R1 = 0.95 × best 20-minute power over $rides rides with power in the last $weeks weeks. "
                val model = "a learned model was not adopted (docs/experiments.md stage 3)"
                if (within) {
                    rule + "Against athletes' own FTP settings R1 was off by $DEV_MAE_WATTS W on average (dev set) and " +
                        "$SEALED_TEST_MAE_WATTS W on the sealed test set, running about $SEALED_TEST_BIAS_WATTS W low; " + model
                } else {
                    rule + "The measured error applies to $MEASURED_WINDOW_WEEKS-week windows with at least " +
                        "$MEASURED_MIN_RIDES power rides; this window was not measured; " + model
                }
            },
        )
    }
}
