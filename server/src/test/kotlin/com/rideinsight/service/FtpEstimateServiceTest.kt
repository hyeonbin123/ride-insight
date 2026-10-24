package com.rideinsight.service

// [파일 역할] FtpEstimateService의 순수 부분 단위 테스트. (1) 측정 조건 판정 withinMeasuredRange의 경계. (2) 서비스가
// 추정값과 함께 보여 주는 3단계 오차 상수(dev MAE 31 W, 봉인 test MAE 29 W, 편향 17 W 과소)가 커밋된
// 보고서(reports/ftp/dev.json·test.json)의 R1 값과 같은지.
// [왜 필요한가] 상수는 사람이 보고서를 보고 옮겨 적은 값이라, 보고서가 바뀌는 경로(새 봉인 세트, 추정기 교체)에서 상수를
// 잊으면 화면이 옛 오차를 보여 준다. 이 테스트가 그 불일치를 잡는다. 지금은 보고서와 같다(dev R1 MAE 30.97, test 29.17, 편향
// −17.15). 조건 판정은 응답에서 오차 숫자를 넣고 빼는 기준이라 경계를 고정해 둔다.
// [주의] 보고서는 저장소 루트의 reports/ 아래라, Gradle 테스트의 작업 디렉터리(server/)에서 `../reports/ftp`로 읽는다. 저장소
// 전체를 받아야 통과한다(서버 Docker 빌드는 -x test라 무관). 파일이 없으면 건너뛰지 않고 이유를 적어 실패한다.

import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class FtpEstimateServiceTest {
    private val reports = File("../reports/ftp")
    private val mapper = JsonMapper.builder().build()

    // 측정 조건(docs/experiments.md 3단계 데이터: 창 42일 = 6주, 창 안 파워 라이딩 8개 미만인 단위 제외)의 경계. 5·7주는
    // 창 길이가 달라 밖이고(측정은 6주만 했다), 라이딩 7개는 측정에서 뺀 단위라 밖이다. 대시보드가 쓰는 12·52주 토글도 밖이다.
    @Test
    fun `withinMeasuredRange is exactly six weeks with at least eight rides`() {
        assertEquals(6, FtpEstimateService.MEASURED_WINDOW_WEEKS)
        assertEquals(8, FtpEstimateService.MEASURED_MIN_RIDES)
        assertTrue(FtpEstimateService.withinMeasuredRange(6, 8))
        assertTrue(FtpEstimateService.withinMeasuredRange(6, 40))
        assertFalse(FtpEstimateService.withinMeasuredRange(6, 7))
        assertFalse(FtpEstimateService.withinMeasuredRange(6, 0))
        assertFalse(FtpEstimateService.withinMeasuredRange(5, 8))
        assertFalse(FtpEstimateService.withinMeasuredRange(7, 8))
        assertFalse(FtpEstimateService.withinMeasuredRange(12, 40))
        assertFalse(FtpEstimateService.withinMeasuredRange(52, 40))
    }

    // 보고서 대조: dev.json의 R1 MAE 점추정(30.97 → 31), test.json의 R1 MAE(29.17 → 29)와 편향(−17.15 → −17). 상수는 편향의
    // 크기(17)만 들고 컨트롤러가 부호를 뒤집어 −17로 돌려주므로, 보고서 편향이 음수(과소 추정)인 것도 함께 확인한다. 반올림은
    // 가장 가까운 정수다.
    @Test
    fun `error constants match the committed stage 3 reports`() {
        val dev = report("dev.json")
        val test = report("test.json")
        assertEquals("dev", dev.path("split").asString())
        assertEquals("test", test.path("split").asString())
        val devMae = dev.path("methods").path("R1").path("mae").path("point").asDouble()
        val testMae = test.path("methods").path("R1").path("mae").path("point").asDouble()
        val testBias = test.path("methods").path("R1").path("bias").path("point").asDouble()
        assertEquals(FtpEstimateService.DEV_MAE_WATTS, devMae.roundToInt(), "dev R1 MAE $devMae")
        assertEquals(FtpEstimateService.SEALED_TEST_MAE_WATTS, testMae.roundToInt(), "test R1 MAE $testMae")
        assertEquals(-FtpEstimateService.SEALED_TEST_BIAS_WATTS, testBias.roundToInt(), "test R1 bias $testBias")
        assertTrue(testBias < 0, "the note says R1 runs low, so the sealed test bias must be negative: $testBias")
    }

    // 보고서 JSON 하나를 읽는다. 없으면 저장소 전체 체크아웃이 필요하다는 메시지로 실패한다.
    private fun report(name: String): JsonNode {
        val file = File(reports, name)
        assertTrue(file.isFile, "${file.path} not found: run the server tests from the full repository checkout (reports/ftp is committed)")
        return mapper.readTree(file)
    }
}
