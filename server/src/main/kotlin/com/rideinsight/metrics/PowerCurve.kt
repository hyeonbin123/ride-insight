package com.rideinsight.metrics

// [파일 역할] 활동 하나의 전 구간 최대 평균 파워 곡선(파워 커브)을 계산하는 순수 함수. 1초부터 라이딩 길이까지
// 로그 간격 지속 시간마다 MeanMaximalPower.best(3000 W 가드 없는 값)를 구한다. 점은 300개 이하다(docs/api.md).
// [설계 이유] 처음에는 GET /api/activities/{id}/power-curve가 요청마다 저장된 샘플 전체를 DB에서 읽어 이 계산을
// 다시 했다. 샘플 50만 개짜리 활동이면 요청 하나가 수십 MB를 읽고 지속 시간 300개 × 50만 번을 돌았고, 2026-10-15
// 측정에서 이런 조회 10건을 동시에 보내자 힙 512 MB 서버가 OutOfMemoryError로 500을 냈다(docs/experiments.md 5단계
// '업로드 자원 증폭'). 그래서 업로드할 때 한 번 계산해 activity_metrics.curve에 저장하고(MetricsService), 조회는
// 저장된 점만 읽는다. 계산 규칙은 ActivityService에 있던 것을 그대로 옮겼다.

/** 활동 하나의 전 구간 파워 커브(지속 시간 초 → 최대 평균 W). */
object PowerCurve {
    /** 곡선의 최대 점 수(docs/api.md: 로그 간격으로 300점 이하). */
    const val MAX_POINTS = 300

    /**
     * 곡선의 점. 기록 간격을 추정할 수 없으면(샘플 2개 미만 등) 빈 목록. 최대 지속 시간 = 라이딩 길이(마지막
     * secs + 기록 간격)이고, 라이딩보다 긴 구간은 best가 null이라 건너뛴다.
     */
    fun points(samples: RideSamples): List<Pair<Int, Double>> {
        if (samples.isEmpty) return emptyList()
        val recInt = RecordingInterval.estimate(samples.secs) ?: return emptyList()
        val maxSecs = (samples.secs.last() + recInt).toInt()
        val points = ArrayList<Pair<Int, Double>>()
        for (d in durations(maxSecs)) {
            val w = MeanMaximalPower.best(samples, recInt, d.toDouble()) ?: continue
            points.add(d to w)
        }
        return points
    }

    /**
     * [maxSecs]까지의 로그 간격 지속 시간 목록. 1초부터 시작해 다음 값 = max(d + 1, d × growth)로 늘리는 격자에
     * 16개 표준 구간과 최대 길이를 더한다(정렬된 집합이라 중복은 한 번만). 점이 [MAX_POINTS]를 넘으면 growth를
     * 1.5배씩 키워 다시 만든다(Codex 2차 검토 지적 11: 처음에는 300점을 넘을 수 있었다).
     */
    fun durations(maxSecs: Int): List<Int> {
        var growth = 1.04
        while (true) {
            val out = sortedSetOf<Int>()
            var d = 1.0
            while (d <= maxSecs) {
                out.add(d.toInt())
                d = maxOf(d + 1, d * growth)
            }
            for (w in MeanMaximalPower.STANDARD_WINDOWS_SECS) if (w <= maxSecs) out.add(w)
            if (maxSecs >= 1) out.add(maxSecs)
            if (out.size <= MAX_POINTS) return out.toList()
            growth *= 1.5 // 비정상 라이딩(예: 일주일 떨어진 샘플 두 개)은 더 성긴 격자가 필요하다
        }
    }
}
