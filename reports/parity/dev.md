# 파리티 결과: dev

- 라이딩 34332개, 선수 100명, 기록 간격 그룹 {'1s': 34246, 'other': 85, '5s': 1}
- 파워 결측 없는 라이딩 34332, 1% 미만 0, 1% 이상 0
- 3000 W 이상 샘플이 있는 라이딩 25, 음수 파워 0, 깨진 행 18, 타임스탬프 흔들림 167, 정수 TSS(수동 입력 추정) 177, GC에 IF는 있고 TSS는 없는 라이딩 748
- GC가 IF에 쓴 임계값이 cp_setting과 1 W 넘게 다른 라이딩: 3294 / 31784

| 가설 | 일치율 [95% 구간] | 판정 |
|---|---|---|
| H1 NP (1초, ≤1 W) | 99.99% [99.97, 100.00] (n=34246) | 지지 |
| H2 MMP (1초, 16구간, ≤1 W) | 99.96% [99.94, 99.97] (n=34246) | 지지 |
| H3 TSS (1초, GC 임계값, 정수·부재 TSS 제외, ≤0.5) | 99.87% [99.83, 99.91] (n=30786) | 지지 |
| H4 NP (1초 아님) | 70.93% [61.63, 80.23] (n=86); 차이(1초 - 그 외) {'point': 0.29055167196576337, 'ci': [0.19755761664237825, 0.3836340587731176]} | 지지 |

## 차이 분포 (|ours - GC|)

| 지표 | n | 중앙값 | p95 | 최대 |
|---|---|---|---|---|
| np_1s | 34246 | 0.000 | 0.000 | 4.189 |
| np_other | 80 | 0.195 | 14.161 | 52.285 |
| tss_1s_gc_threshold | 30786 | 0.000 | 0.002 | 209.208 |
| tss_1s_cp_setting | 30958 | 0.000 | 10.379 | 619.263 |
| mmp_1s_critical_power | 34208 | 0.000 | 0.000 | 729.000 |
| mmp_5s_critical_power | 34223 | 0.000 | 0.000 | 433.800 |
| mmp_10s_critical_power | 34219 | 0.000 | 0.000 | 1604.400 |
| mmp_15s_critical_power | 34215 | 0.000 | 0.000 | 1867.200 |
| mmp_20s_critical_power | 34214 | 0.000 | 0.000 | 1391.450 |
| mmp_30s_critical_power | 34202 | 0.000 | 0.000 | 1543.100 |
| mmp_1m_critical_power | 34179 | 0.000 | 0.000 | 834.117 |
| mmp_2m_critical_power | 34151 | 0.000 | 0.000 | 474.975 |
| mmp_3m_critical_power | 34107 | 0.000 | 0.000 | 950.144 |
| mmp_5m_critical_power | 33980 | 0.000 | 0.000 | 627.707 |
| mmp_8m_critical_power | 33727 | 0.000 | 0.000 | 265.712 |
| mmp_10m_critical_power | 33536 | 0.000 | 0.000 | 347.287 |
| mmp_20m_critical_power | 32056 | 0.000 | 0.000 | 177.364 |
| mmp_30m_critical_power | 29791 | 0.000 | 0.000 | 118.243 |
| mmp_60m_critical_power | 21075 | 0.000 | 0.000 | 46.138 |
| mmp_90m_critical_power | 12994 | 0.000 | 0.000 | 35.054 |

## 구간별 MMP 일치율 (1초)

- 1s_critical_power: 99.88%
- 5s_critical_power: 99.94%
- 10s_critical_power: 99.96%
- 15s_critical_power: 99.96%
- 20s_critical_power: 99.96%
- 30s_critical_power: 99.96%
- 1m_critical_power: 99.97%
- 2m_critical_power: 99.97%
- 3m_critical_power: 99.97%
- 5m_critical_power: 99.97%
- 8m_critical_power: 99.97%
- 10m_critical_power: 99.96%
- 20m_critical_power: 99.96%
- 30m_critical_power: 99.96%
- 60m_critical_power: 99.96%
- 90m_critical_power: 99.97%

## NP 차이가 가장 큰 라이딩 5개 (1초)

- 8cddf98c 2020_04_26_14_51_00.csv n=6564 missing=0 ours=230.48874893826405 gc=226.30005 diff=4.19
- 50a1304c 2015_03_21_10_37_58.csv n=11947 missing=0 ours=157.88419117933066 gc=161.57125 diff=3.69
- 8cddf98c 2020_03_14_12_54_20.csv n=8520 missing=0 ours=184.4027130174508 gc=186.07411 diff=1.67
- 8cddf98c 2020_03_19_13_06_38.csv n=6891 missing=0 ours=214.29208611597394 gc=213.18353 diff=1.11
- 8cddf98c 2020_04_21_13_26_18.csv n=8534 missing=0 ours=214.66072696991856 gc=213.57229 diff=1.09
