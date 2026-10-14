"""Kotlin ``parity`` 도구가 쓴 1단계 파리티 JSONL을 분석한다(docs/experiments.md).

사용법: ``python -m ride_insight_ml.parity --jsonl ../reports/parity/dev.jsonl --out ../reports/parity/dev``
실행하면 ``<out>.json``(모든 수치)과 ``<out>.md``(요약 표)를 쓴다.
"""

# [파일 역할] 1단계(지표 구현의 정확성) 판정 스크립트. Kotlin parity 도구(ParityTool.kt)가 라이딩마다 '우리
# 구현의 NP·TSS·MMP 16구간'과 'GoldenCheetah 메타데이터 값'을 나란히 적은 JSONL을 읽어, 미리 정한 허용 오차
# 안에서 일치하는 비율과 부트스트랩 95% 구간을 내고 H1~H4를 판정한다.
# [왜 필요한가] 직접 구현한 지표가 맞는지를 '널리 쓰이는 다른 구현(GoldenCheetah)이 같은 데이터로 계산한 값'과
# 대조해 수치로 보이려는 것이다. 판정 기준(1 W, TSS 0.5, 99%)은 측정 전에 docs/experiments.md 1단계에 적었다.
# [흐름] load(JSONL → 표, 파생 열: GC 임계값, GC 임계값으로 다시 계산한 TSS, 정수 TSS, 흔들림, 간격 그룹) →
# analyse(가설별 일치 여부 → 부트스트랩 → 판정, 보고 항목) → to_markdown.
# [결과] 봉인 세트(선수 100명, 라이딩 32,907개)에서 H1 NP 99.61%, H2 MMP 99.65%, H3 TSS 99.97% 지지, H4
# 기각(reports/parity/sealed.md).

from __future__ import annotations

import argparse
import json
from pathlib import Path

# numpy: 부트스트랩 재표집(seed 0 Generator)과 조건부 계산(np.where). pandas: 라이딩 표 처리.
import numpy as np
import pandas as pd

# 판정 상수(docs/experiments.md 1단계 가설).
# - TOL_WATTS: NP·MMP가 GC와 '일치'로 인정되는 차이 1 W 이하
# - TOL_TSS: TSS 차이 0.5 이하
# - TARGET_RATE: 일치율 99% 이상이면 가설 지지(구간 하한 기준)
# - BOOTSTRAP_N·SEED: 공통 규칙의 라이딩 단위 부트스트랩 2,000회, seed 0
TOL_WATTS = 1.0
TOL_TSS = 0.5
TARGET_RATE = 0.99
BOOTSTRAP_N = 2000
SEED = 0


# JSONL을 라이딩 한 행짜리 DataFrame으로 읽고 판정에 필요한 파생 열을 붙인다. 호출 위치: main,
# tests/test_parity.py.
# 라이딩이 하나도 없으면 분명한 메시지로 끝낸다. 짝지어진 라이딩이 없는 입력에서 분석이 KeyError로 죽던 것을
# 고친 것이다(docs/ai-verification.md 1단계).
def load(jsonl: Path) -> pd.DataFrame:
    rows = []
    for line in jsonl.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        row = {
            "athlete": d["athlete"],
            "date": d["date"],
            "csv": d["csv"],
            "rec_int": d["rec_int"],
            "rec_int_share": d.get("rec_int_share"),
            "samples": d["samples"],
            "power_missing": d["power_missing"],
            "bad_rows": d["bad_rows"],
            "over_3000": d["over_3000"],
            "negative": d["negative"],
            "cp": d["cp"],
            "np_ours": d["ours"]["np"],
            "np_gc": d["gc"]["np"],
            "tss_ours": d["ours"]["tss"],
            "tss_gc": d["gc"]["tss"],
            "if_gc": d["gc"]["if"],
            "workout_time_gc": d["gc"]["workout_time"],
        }
        # MMP 16구간은 구간마다 열 하나로 펼친다(mmp_ours_<키>, mmp_gc_<키>).
        for k, v in d["ours"]["mmp"].items():
            row[f"mmp_ours_{k}"] = v
        for k, v in d["gc"]["mmp"].items():
            row[f"mmp_gc_{k}"] = v
        rows.append(row)
    if not rows:
        raise SystemExit(f"no rides in {jsonl}: did the parity tool find the athletes' zips and index files?")
    df = pd.DataFrame(rows)
    # 파워 결측 비율(보고용). samples가 0이어도 0으로 나누지 않게 1 이상으로 자른다.
    df["missing_ratio"] = df["power_missing"] / df["samples"].clip(lower=1)
    # 규칙 변경 2: GC가 IF에 실제로 쓴 임계값 = NP / IF로 역산한다(thr_gc). 이 값으로 우리 NP에서 TSS를 다시
    # 계산해(tss_ours_thr) GC TSS와 비교한다. TSS = 시간(h) × IF² × 100이고 IF = NP / 임계값이므로 100 × hours
    # × (NP / thr)²가 같은 식이다. 시간은 샘플 수 × 기록 간격(GC의 'NP × 샘플 수 × recIntSecs' 정의와 같은
    # 기준). 이 임계값은 존 설정의 FTP라서 cp_setting(존 설정의 CP)과 다를 수 있다.
    np_gc = pd.to_numeric(df["np_gc"], errors="coerce")
    if_gc = pd.to_numeric(df["if_gc"], errors="coerce")
    df["thr_gc"] = np.where(if_gc > 0, np_gc / if_gc, np.nan)
    np_ours = pd.to_numeric(df["np_ours"], errors="coerce")
    hours = df["samples"] * df["rec_int"] / 3600.0
    df["tss_ours_thr"] = np.where(df["thr_gc"] > 0, 100.0 * hours * (np_ours / df["thr_gc"]) ** 2, np.nan)
    # cp_setting과 GC 임계값이 1 W 넘게 다른 라이딩 표시(보고용, 3단계 라벨 정의의 근거가 됐다).
    df["cp_differs"] = (df["cp"] - df["thr_gc"]).abs() > 1.0
    # 규칙 변경 3: GC TSS(coggan_tss)가 정확히 정수(1e-6 안)면 선수가 직접 입력해 덮어쓴 값으로 보고 H3에서
    # 뺀다. 개발 세트에서 정수 값 172개 중 170개가 계산한 TSS와 어긋나, 정수 TSS는 거의 항상 수동 입력이었다.
    tss_gc = pd.to_numeric(df["tss_gc"], errors="coerce")
    df["tss_gc_integer"] = tss_gc.notna() & ((tss_gc - tss_gc.round()).abs() < 1e-6)
    # jitter: 최빈 간격이 전체 양수 간격의 90% 미만이면 '타임스탬프가 흔들리는 라이딩'(규칙의 보고 항목).
    # interval_group: 기록 간격 1초 / 5초 / 그 외. H1~H3은 1초 라이딩만, H4는 1초 아닌 라이딩을 본다.
    df["jitter"] = pd.to_numeric(df["rec_int_share"], errors="coerce") < 0.9
    df["interval_group"] = np.where(df["rec_int"] == 1.0, "1s", np.where(df["rec_int"] == 5.0, "5s", "other"))
    return df


# 행마다 일치 여부(True/False). 규칙: 둘 다 값이 없으면 일치, 한쪽만 있으면 불일치, 둘 다 있으면 |차이| ≤
# tol일 때 일치. GC 쪽은 0도 '없음'으로 본다(GC는 결과가 없거나 3000 W 이상일 때 0을 쓴다; Codex 1차 검토 지적
# 5로 규칙에 명시). 우리 Kotlin 구현도 같은 경우에 0을 내므로(MeanMaximalPower.peakPower의 3000 W 가드)
# 우리 쪽의 0도 '없음'으로 본다. 처음 구현은 우리 쪽은 NaN만 '없음'으로 봐서, 두 구현이 똑같이 가드에 걸려 둘
# 다 0인 쌍을 불일치로 셌다(2026-10-13 수정, docs/experiments.md 규칙 변경 9).
# 호출 위치: analyse, tests/test_parity.py.
def agree(ours: pd.Series, theirs: pd.Series, tol: float) -> pd.Series:
    """행마다 일치 여부를 낸다. 양쪽 모두 값이 없으면(어느 쪽이든 None/NaN 또는 0) 일치, 한쪽만
    없으면 불일치다. 두 구현 모두 '값 없음'에 0을 쓴다(예: PeakPower의 3000 W 가드)."""
    o = pd.to_numeric(ours, errors="coerce")
    t = pd.to_numeric(theirs, errors="coerce")
    t_absent = t.isna() | (t == 0.0)
    o_absent = o.isna() | (o == 0.0)
    both_absent = t_absent & o_absent
    both_present = ~t_absent & ~o_absent
    return both_absent | (both_present & ((o - t).abs() <= tol))


# 일치 여부 배열의 비율과 라이딩 단위 부트스트랩 95% 구간. (n × 라이딩 수) 크기의 인덱스 행렬을 한 번에 뽑아
# 벡터 연산으로 2,000개 재표집 비율을 계산한다(파이썬 반복문보다 빠르다). 대신 2,000 × 라이딩 수 크기의 배열을
# 만들므로 라이딩이 수만 개면 메모리를 수백 MB 쓴다. 입력이 비면 rate None.
def bootstrap_rate(mask: pd.Series, n: int = BOOTSTRAP_N, seed: int = SEED) -> dict:
    values = mask.to_numpy(dtype=float)
    if len(values) == 0:
        return {"n": 0, "rate": None, "ci": [None, None]}
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, len(values), size=(n, len(values)))
    rates = values[idx].mean(axis=1)
    return {
        "n": int(len(values)),
        "rate": float(values.mean()),
        "ci": [float(np.quantile(rates, 0.025)), float(np.quantile(rates, 0.975))],
    }


# '비율 ≥ target' 가설 판정. 구간 하한 ≥ 99%면 지지, 상한 < 99%면 기각, 걸치면 판정 불가(규칙의 판정 방식).
def verdict_at_least(ci: list, target: float) -> str:
    if ci[0] is None:
        return "판정 불가"
    if ci[0] >= target:
        return "지지"
    if ci[1] < target:
        return "기각"
    return "판정 불가"


# |우리 − GC| 차이 분포(보고용): 둘 다 값이 있는 행의 개수, 중앙값, p95, 최대.
def diff_stats(ours: pd.Series, theirs: pd.Series) -> dict:
    o = pd.to_numeric(ours, errors="coerce")
    t = pd.to_numeric(theirs, errors="coerce")
    d = (o - t).dropna().abs()
    if d.empty:
        return {"n": 0}
    return {
        "n": int(len(d)),
        "median": float(d.median()),
        "p95": float(d.quantile(0.95)),
        "max": float(d.max()),
    }


# 가설 판정과 보고 항목을 계산한다. 호출 위치: main, tests/test_parity.py.
# - H1: 1초 라이딩의 NP 일치율
# - H2: 1초 라이딩의 (라이딩, 16구간) MMP 일치율
# - H3: 1초 라이딩 중 GC 임계값이 있고, GC TSS가 정수가 아니며 0·결측이 아닌 라이딩의 TSS 일치율
# - H4: 1초가 아닌 라이딩의 NP 일치율과, '1초 − 그 외' 차이가 5%p 이상인가
def analyse(df: pd.DataFrame) -> dict:
    mmp_keys = [c[len("mmp_gc_") :] for c in df.columns if c.startswith("mmp_gc_")]
    one_s = df[df["interval_group"] == "1s"]
    other = df[df["interval_group"] != "1s"]
    # 규칙 변경 4: GC에 IF는 있는데 TSS가 없거나 0인 라이딩은 H3에서 뺀다(계산 차이가 아니라 GC가 TSS를
    # 저장하지 않은 것). 개발 세트에서는 라이딩 31,703개 중 745개(선수 13명)가 그랬다. with_cp는 이름과 달리
    # cp_setting이 아니라 GC 임계값(thr_gc)이 있는 라이딩을 뜻한다.
    tss_gc_present = pd.to_numeric(one_s["tss_gc"], errors="coerce").fillna(0.0) != 0.0
    with_cp = one_s[one_s["thr_gc"].notna() & ~one_s["tss_gc_integer"] & tss_gc_present]

    np_agree_1s = agree(one_s["np_ours"], one_s["np_gc"], TOL_WATTS)
    np_agree_other = agree(other["np_ours"], other["np_gc"], TOL_WATTS)
    tss_agree = agree(with_cp["tss_ours_thr"], with_cp["tss_gc"], TOL_TSS)

    # mmp_pairs: 모든 (라이딩, 구간) 쌍을 한 줄로 이어 붙인 일치 여부(쌍 단위 비율 보고용).
    # per_ride_mmp → ride_mmp_rate: 라이딩마다 16쌍의 일치 비율. 부트스트랩은 쌍이 아니라 라이딩을 다시 뽑아야
    # 한다(같은 라이딩의 16쌍은 독립이 아니므로). 라이딩마다 쌍 수가 16으로 같아 라이딩 비율의 평균은 쌍 단위
    # 비율과 같다.
    mmp_pairs = pd.concat(
        [agree(one_s[f"mmp_ours_{k}"], one_s[f"mmp_gc_{k}"], TOL_WATTS) for k in mmp_keys], ignore_index=True
    )
    # MMP는 라이딩 단위로 재표집한다: 라이딩을 다시 뽑고, 뽑힌 라이딩의 16쌍을 모두 센다.
    per_ride_mmp = pd.concat(
        [agree(one_s[f"mmp_ours_{k}"], one_s[f"mmp_gc_{k}"], TOL_WATTS) for k in mmp_keys], axis=1
    )
    ride_mmp_rate = per_ride_mmp.mean(axis=1) if not per_ride_mmp.empty else pd.Series(dtype=float)

    h1 = bootstrap_rate(np_agree_1s)
    h2 = bootstrap_rate(ride_mmp_rate)
    h3 = bootstrap_rate(tss_agree)
    h4_other = bootstrap_rate(np_agree_other)
    # H4의 차이 구간: 1초 그룹과 그 외 그룹을 각각 독립으로 다시 뽑아 '1초 일치율 − 그 외 일치율'을 2,000번
    # 구한다(규칙의 독립 재표집 차이, Codex 1차 검토 지적 7). 한쪽 그룹이 비면 판정 불가.
    h4_diff = None
    if len(np_agree_1s) and len(np_agree_other):
        rng = np.random.default_rng(SEED)
        a = np_agree_1s.to_numpy(dtype=float)
        b = np_agree_other.to_numpy(dtype=float)
        diffs = [
            a[rng.integers(0, len(a), len(a))].mean() - b[rng.integers(0, len(b), len(b))].mean()
            for _ in range(BOOTSTRAP_N)
        ]
        h4_diff = {
            "point": float(a.mean() - b.mean()),
            "ci": [float(np.quantile(diffs, 0.025)), float(np.quantile(diffs, 0.975))],
        }

    # 차이 하한 ≥ 5%p면 지지, 상한 < 5%p면 기각. 봉인 세트는 3.3%p [2.4, 4.1]로 기각됐다(5초 기록이 많았고
    # GC와 잘 맞았다).
    def h4_verdict() -> str:
        if h4_diff is None:
            return "판정 불가"
        lo, hi = h4_diff["ci"]
        if lo >= 0.05:
            return "지지"
        if hi < 0.05:
            return "기각"
        return "판정 불가"

    # 1초 라이딩 중 NP 차이가 가장 큰 5개(보고의 '어긋난 라이딩 예시'). 봉인 세트에서는 메타데이터에 파워가
    # 있다는데 CSV 파워 열이 빈 라이딩들이었다.
    worst = one_s.assign(np_diff=(one_s["np_ours"] - one_s["np_gc"]).abs())
    worst = worst.sort_values("np_diff", ascending=False).head(5)
    # 보고 항목(판정 없음)도 함께 돌려준다: 간격 그룹·간격 값 분포, 파워 결측 비율 구간, 3000 W 이상·음수
    # 파워·깨진 행·흔들림이 있는 라이딩 수, 정수 TSS 수, IF는 있고 TSS가 없는 수, cp_setting과 GC 임계값이
    # 다른 라이딩 수, 지표별 차이 분포, 구간별 MMP 일치율.
    return {
        "rides": int(len(df)),
        "athletes": int(df["athlete"].nunique()),
        "interval_groups": df["interval_group"].value_counts().to_dict(),
        "rec_int_values": df["rec_int"].value_counts().head(10).to_dict(),
        "missing_ratio": {
            "zero": int((df["missing_ratio"] == 0).sum()),
            "under_1pct": int(((df["missing_ratio"] > 0) & (df["missing_ratio"] < 0.01)).sum()),
            "over_1pct": int((df["missing_ratio"] >= 0.01).sum()),
        },
        "rides_with_over_3000": int((df["over_3000"] > 0).sum()),
        "rides_with_negative": int((df["negative"] > 0).sum()),
        "rides_with_bad_rows": int((df["bad_rows"] > 0).sum()),
        "rides_with_jitter": int(df["jitter"].sum()),
        "rides_with_integer_gc_tss": int(df["tss_gc_integer"].sum()),
        "rides_with_if_but_no_gc_tss": int(
            (df["thr_gc"].notna() & (pd.to_numeric(df["tss_gc"], errors="coerce").fillna(0.0) == 0.0)).sum()
        ),
        "cp_differs_from_gc_threshold": {
            "n": int(df["thr_gc"].notna().sum()),
            "differs": int((df["cp_differs"] & df["thr_gc"].notna()).sum()),
        },
        "H1_np_1s": {**h1, "verdict": verdict_at_least(h1["ci"], TARGET_RATE)},
        "H2_mmp_1s": {
            **h2,
            "pairs": int(len(mmp_pairs)),
            "pair_rate": float(mmp_pairs.mean()) if len(mmp_pairs) else None,
            "verdict": verdict_at_least(h2["ci"], TARGET_RATE),
        },
        "H3_tss_1s": {**h3, "verdict": verdict_at_least(h3["ci"], TARGET_RATE)},
        "H4_np_other": {**h4_other, "diff_1s_minus_other": h4_diff, "verdict": h4_verdict()},
        "diffs": {
            "np_1s": diff_stats(one_s["np_ours"], one_s["np_gc"]),
            "np_other": diff_stats(other["np_ours"], other["np_gc"]),
            "tss_1s_gc_threshold": diff_stats(with_cp["tss_ours_thr"], with_cp["tss_gc"]),
            "tss_1s_cp_setting": diff_stats(one_s["tss_ours"], one_s["tss_gc"]),
            **{f"mmp_{k}": diff_stats(one_s[f"mmp_ours_{k}"], one_s[f"mmp_gc_{k}"]) for k in mmp_keys},
        },
        "mmp_rate_by_window": {
            k: float(agree(one_s[f"mmp_ours_{k}"], one_s[f"mmp_gc_{k}"], TOL_WATTS).mean()) for k in mmp_keys
        }
        if len(one_s)
        else {},
        "worst_np_1s": worst[
            ["athlete", "csv", "rec_int", "samples", "power_missing", "np_ours", "np_gc", "np_diff"]
        ].to_dict("records"),
    }


# analyse 결과를 Markdown 보고서(reports/parity/*.md)로 만든다: 데이터 요약, 가설 표(일치율 [구간] (n), 판정),
# 차이 분포 표, 구간별 MMP 일치율, NP 차이 상위 5개. 내부 함수 rate()가 비율을 백분율 문자열로 바꾼다.
def to_markdown(r: dict, title: str) -> str:
    def rate(h):
        if h["rate"] is None:
            return "-"
        return f"{h['rate'] * 100:.2f}% [{h['ci'][0] * 100:.2f}, {h['ci'][1] * 100:.2f}] (n={h['n']})"

    miss = r["missing_ratio"]
    h4 = r["H4_np_other"]
    lines = [
        f"# 파리티 결과: {title}",
        "",
        f"- 라이딩 {r['rides']}개, 선수 {r['athletes']}명, 기록 간격 그룹 {r['interval_groups']}",
        f"- 파워 결측 없는 라이딩 {miss['zero']}, 1% 미만 {miss['under_1pct']}, 1% 이상 {miss['over_1pct']}",
        f"- 3000 W 이상 샘플이 있는 라이딩 {r['rides_with_over_3000']}, "
        f"음수 파워 {r['rides_with_negative']}, 깨진 행 {r['rides_with_bad_rows']}, "
        f"타임스탬프 흔들림 {r['rides_with_jitter']}, "
        f"정수 TSS(수동 입력 추정) {r['rides_with_integer_gc_tss']}, "
        f"GC에 IF는 있고 TSS는 없는 라이딩 {r['rides_with_if_but_no_gc_tss']}",
        f"- GC가 IF에 쓴 임계값이 cp_setting과 1 W 넘게 다른 라이딩: "
        f"{r['cp_differs_from_gc_threshold']['differs']} / {r['cp_differs_from_gc_threshold']['n']}",
        "",
        "| 가설 | 일치율 [95% 구간] | 판정 |",
        "|---|---|---|",
        f"| H1 NP (1초, ≤1 W) | {rate(r['H1_np_1s'])} | {r['H1_np_1s']['verdict']} |",
        f"| H2 MMP (1초, 16구간, ≤1 W) | {rate(r['H2_mmp_1s'])} | {r['H2_mmp_1s']['verdict']} |",
        f"| H3 TSS (1초, GC 임계값, 정수·부재 TSS 제외, ≤0.5) | {rate(r['H3_tss_1s'])} "
        f"| {r['H3_tss_1s']['verdict']} |",
        f"| H4 NP (1초 아님) | {rate(h4)}; 차이(1초 - 그 외) {h4['diff_1s_minus_other']} | {h4['verdict']} |",
        "",
        "## 차이 분포 (|ours - GC|)",
        "",
        "| 지표 | n | 중앙값 | p95 | 최대 |",
        "|---|---|---|---|---|",
    ]
    for k, d in r["diffs"].items():
        if d.get("n"):
            lines.append(f"| {k} | {d['n']} | {d['median']:.3f} | {d['p95']:.3f} | {d['max']:.3f} |")
    lines += ["", "## 구간별 MMP 일치율 (1초)", ""]
    for k, v in r["mmp_rate_by_window"].items():
        lines.append(f"- {k}: {v * 100:.2f}%")
    lines += ["", "## NP 차이가 가장 큰 라이딩 5개 (1초)", ""]
    for w in r["worst_np_1s"]:
        lines.append(
            f"- {w['athlete'][:8]} {w['csv']} n={w['samples']} missing={w['power_missing']} "
            f"ours={w['np_ours']} gc={w['np_gc']} diff={w['np_diff']:.2f}"
        )
    return "\n".join(lines) + "\n"


# 명령행 진입점. --jsonl을 분석해 --out.json(모든 수치)과 --out.md(요약)를 쓴다. 보고서 제목은 JSONL 파일
# 이름(dev, sealed 등)이다.
def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--jsonl", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True, help="확장자를 뺀 출력 경로(.json과 .md를 쓴다)")
    a = p.parse_args(argv)
    df = load(a.jsonl)
    result = analyse(df)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    Path(f"{a.out}.json").write_text(
        json.dumps(result, indent=1, ensure_ascii=False, default=str), encoding="utf-8"
    )
    Path(f"{a.out}.md").write_text(to_markdown(result, a.jsonl.stem), encoding="utf-8")
    print(f"wrote {a.out}.json and {a.out}.md")


if __name__ == "__main__":
    main()
