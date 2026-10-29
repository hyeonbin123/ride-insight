# 파리티 결과: sealed

- 라이딩 32907개, 선수 100명, 기록 간격 그룹 {'1s': 31150, '5s': 1149, 'other': 608}
- 파워 결측 없는 라이딩 32791, 1% 미만 0, 1% 이상 116
- 3000 W 이상 샘플이 있는 라이딩 60, 음수 파워 0, 깨진 행 11, 타임스탬프 흔들림 315, 정수 TSS(수동 입력 추정) 28, GC에 IF는 있고 TSS는 없는 라이딩 478
- GC가 IF에 쓴 임계값이 cp_setting과 1 W 넘게 다른 라이딩: 4094 / 30542

| 가설 | 일치율 [95% 구간] | 판정 |
|---|---|---|
| H1 NP (1초, ≤1 W) | 99.61% [99.54, 99.68] (n=31150) | 지지 |
| H2 MMP (1초, 16구간, ≤1 W) | 99.65% [99.60, 99.71] (n=31150) | 지지 |
| H3 TSS (1초, GC 임계값, 정수·부재 TSS 제외, ≤0.5) | 99.97% [99.94, 99.99] (n=28337) | 지지 |
| H4 NP (1초 아님) | 96.36% [95.45, 97.21] (n=1757); 차이(1초 - 그 외) {'point': 0.03254129549218854, 'ci': [0.023650300883144796, 0.0407347272227303]} | 기각 |

## 차이 분포 (|ours - GC|)

| 지표 | n | 중앙값 | p95 | 최대 |
|---|---|---|---|---|
| np_1s | 31148 | 0.000 | 0.000 | 262.819 |
| np_other | 1750 | 0.000 | 0.000 | 74.405 |
| tss_1s_gc_threshold | 28337 | 0.000 | 0.002 | 60.575 |
| tss_1s_cp_setting | 28365 | 0.000 | 7.999 | 354.612 |
| mmp_1s_critical_power | 31077 | 0.000 | 0.000 | 947.505 |
| mmp_5s_critical_power | 31117 | 0.000 | 0.000 | 2348.400 |
| mmp_10s_critical_power | 31122 | 0.000 | 0.000 | 1163.200 |
| mmp_15s_critical_power | 31118 | 0.000 | 0.000 | 759.933 |
| mmp_20s_critical_power | 31113 | 0.000 | 0.000 | 679.731 |
| mmp_30s_critical_power | 31102 | 0.000 | 0.000 | 591.619 |
| mmp_1m_critical_power | 31059 | 0.000 | 0.000 | 454.965 |
| mmp_2m_critical_power | 30975 | 0.000 | 0.000 | 336.085 |
| mmp_3m_critical_power | 30903 | 0.000 | 0.000 | 1444.017 |
| mmp_5m_critical_power | 30769 | 0.000 | 0.000 | 236.450 |
| mmp_8m_critical_power | 30498 | 0.000 | 0.000 | 191.774 |
| mmp_10m_critical_power | 30271 | 0.000 | 0.000 | 176.039 |
| mmp_20m_critical_power | 28350 | 0.000 | 0.000 | 145.888 |
| mmp_30m_critical_power | 26844 | 0.000 | 0.000 | 136.250 |
| mmp_60m_critical_power | 20557 | 0.000 | 0.000 | 122.681 |
| mmp_90m_critical_power | 12453 | 0.000 | 0.000 | 110.039 |

## 구간별 MMP 일치율 (1초)

- 1s_critical_power: 99.38%
- 5s_critical_power: 99.52%
- 10s_critical_power: 99.56%
- 15s_critical_power: 99.57%
- 20s_critical_power: 99.57%
- 30s_critical_power: 99.58%
- 1m_critical_power: 99.58%
- 2m_critical_power: 99.57%
- 3m_critical_power: 99.58%
- 5m_critical_power: 99.57%
- 8m_critical_power: 99.59%
- 10m_critical_power: 99.70%
- 20m_critical_power: 99.88%
- 30m_critical_power: 99.91%
- 60m_critical_power: 99.94%
- 90m_critical_power: 99.96%

## NP 차이가 가장 큰 라이딩 5개 (1초)

- 9ee81290 2018_03_30_15_51_55.csv n=910 missing=910 ours=0.0 gc=262.81905 diff=262.82
- 9ee81290 2018_01_23_15_06_50.csv n=534 missing=534 ours=0.0 gc=230.98112 diff=230.98
- 9ee81290 2018_03_26_17_41_39.csv n=980 missing=980 ours=0.0 gc=206.38205 diff=206.38
- 9ee81290 2018_03_27_10_43_57.csv n=678 missing=678 ours=0.0 gc=200.04676 diff=200.05
- 9ee81290 2018_03_26_17_11_13.csv n=785 missing=785 ours=0.0 gc=198.21009 diff=198.21
