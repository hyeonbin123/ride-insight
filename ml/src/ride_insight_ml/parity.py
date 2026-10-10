"""Analyse the stage-1 parity JSONL written by the Kotlin ``parity`` tool (docs/experiments.md).

Usage: ``python -m ride_insight_ml.parity --jsonl ../reports/parity/dev.jsonl --out ../reports/parity/dev``
writes ``<out>.json`` (all numbers) and ``<out>.md`` (summary table).
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

TOL_WATTS = 1.0
TOL_TSS = 0.5
TARGET_RATE = 0.99
BOOTSTRAP_N = 2000
SEED = 0


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
            "workout_time_gc": d["gc"]["workout_time"],
        }
        for k, v in d["ours"]["mmp"].items():
            row[f"mmp_ours_{k}"] = v
        for k, v in d["gc"]["mmp"].items():
            row[f"mmp_gc_{k}"] = v
        rows.append(row)
    if not rows:
        raise SystemExit(f"no rides in {jsonl}: did the parity tool find the athletes' zips and index files?")
    df = pd.DataFrame(rows)
    df["missing_ratio"] = df["power_missing"] / df["samples"].clip(lower=1)
    df["interval_group"] = np.where(df["rec_int"] == 1.0, "1s", np.where(df["rec_int"] == 5.0, "5s", "other"))
    return df


def agree(ours: pd.Series, theirs: pd.Series, tol: float) -> pd.Series:
    """Agreement per row: both absent (None/NaN or GC 0) counts as agreement; one side absent as not."""
    o = pd.to_numeric(ours, errors="coerce")
    t = pd.to_numeric(theirs, errors="coerce")
    t_absent = t.isna() | (t == 0.0)
    o_absent = o.isna()
    both_absent = t_absent & o_absent
    both_present = ~t_absent & ~o_absent
    return both_absent | (both_present & ((o - t).abs() <= tol))


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


def verdict_at_least(ci: list, target: float) -> str:
    if ci[0] is None:
        return "판정 불가"
    if ci[0] >= target:
        return "지지"
    if ci[1] < target:
        return "기각"
    return "판정 불가"


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


def analyse(df: pd.DataFrame) -> dict:
    mmp_keys = [c[len("mmp_gc_") :] for c in df.columns if c.startswith("mmp_gc_")]
    one_s = df[df["interval_group"] == "1s"]
    other = df[df["interval_group"] != "1s"]
    with_cp = one_s[one_s["cp"].notna()]

    np_agree_1s = agree(one_s["np_ours"], one_s["np_gc"], TOL_WATTS)
    np_agree_other = agree(other["np_ours"], other["np_gc"], TOL_WATTS)
    tss_agree = agree(with_cp["tss_ours"], with_cp["tss_gc"], TOL_TSS)

    mmp_pairs = pd.concat(
        [agree(one_s[f"mmp_ours_{k}"], one_s[f"mmp_gc_{k}"], TOL_WATTS) for k in mmp_keys], ignore_index=True
    )
    # Ride-level resampling for MMP: resample rides, then count all 16 pairs of each ride.
    per_ride_mmp = pd.concat(
        [agree(one_s[f"mmp_ours_{k}"], one_s[f"mmp_gc_{k}"], TOL_WATTS) for k in mmp_keys], axis=1
    )
    ride_mmp_rate = per_ride_mmp.mean(axis=1) if not per_ride_mmp.empty else pd.Series(dtype=float)

    h1 = bootstrap_rate(np_agree_1s)
    h2 = bootstrap_rate(ride_mmp_rate)
    h3 = bootstrap_rate(tss_agree)
    h4_other = bootstrap_rate(np_agree_other)
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

    def h4_verdict() -> str:
        if h4_diff is None:
            return "판정 불가"
        lo, hi = h4_diff["ci"]
        if lo >= 0.05:
            return "지지"
        if hi < 0.05:
            return "기각"
        return "판정 불가"

    worst = one_s.assign(np_diff=(one_s["np_ours"] - one_s["np_gc"]).abs())
    worst = worst.sort_values("np_diff", ascending=False).head(5)
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
            "tss_1s": diff_stats(with_cp["tss_ours"], with_cp["tss_gc"]),
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
        f"음수 파워 {r['rides_with_negative']}, 깨진 행 {r['rides_with_bad_rows']}",
        "",
        "| 가설 | 일치율 [95% 구간] | 판정 |",
        "|---|---|---|",
        f"| H1 NP (1초, ≤1 W) | {rate(r['H1_np_1s'])} | {r['H1_np_1s']['verdict']} |",
        f"| H2 MMP (1초, 16구간, ≤1 W) | {rate(r['H2_mmp_1s'])} | {r['H2_mmp_1s']['verdict']} |",
        f"| H3 TSS (1초, ≤0.5) | {rate(r['H3_tss_1s'])} | {r['H3_tss_1s']['verdict']} |",
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


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--jsonl", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True, help="output path without extension")
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
