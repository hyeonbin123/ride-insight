"""Stage 3: FTP estimation from six weeks of riding (docs/experiments.md, 3단계).

Sub-commands:

* ``build``     prediction units (athlete, reference date) with window features and labels
* ``evaluate``  fit the candidates on train, report MAE etc. on one split with athlete bootstrap

Inputs: the per-ride JSONL written by the Kotlin ``parity`` task for every sampled athlete
(our NP and 16 mean-maximal powers per ride plus GoldenCheetah's cp_setting / NP / IF), the ride
index directory (gender, year of birth) and the sample manifest (split per athlete).
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path

import numpy as np
import pandas as pd

WINDOW_DAYS = 42
STEP_DAYS = 28
MIN_RIDES = 8
FRESH_DAYS = 28
LABEL_MIN, LABEL_MAX = 50, 600
MMP_KEYS = [
    "1s",
    "5s",
    "10s",
    "15s",
    "20s",
    "30s",
    "1m",
    "2m",
    "3m",
    "5m",
    "8m",
    "10m",
    "20m",
    "30m",
    "60m",
    "90m",
]
CP_FIT_KEYS = {"3m": 180, "5m": 300, "8m": 480, "10m": 600, "20m": 1200}
BOOTSTRAP_N = 2000
SEED = 0
LGBM_PARAMS = dict(
    num_leaves=15,
    learning_rate=0.05,
    n_estimators=400,
    min_child_samples=50,
    subsample=0.8,
    subsample_freq=1,
    colsample_bytree=0.8,
    random_state=SEED,
    verbose=-1,
)


# ----------------------------------------------------------------------------- build


def _ride_rows(jsonl: Path):
    for line in jsonl.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        gc = d["gc"]
        np_gc, if_gc = gc.get("np"), gc.get("if")
        thr = round(np_gc / if_gc) if np_gc and if_gc and if_gc > 0 else None
        row = {
            "athlete": d["athlete"],
            "date": dt.datetime.strptime(d["date"].replace(" UTC", ""), "%Y/%m/%d %H:%M:%S"),
            "np": d["ours"]["np"],
            "hours": d["samples"] * (d["rec_int"] or 0) / 3600.0,
            "thr": thr,
            "cp": d.get("cp"),
            "weight": d.get("weight"),
        }
        for k in MMP_KEYS:
            row[f"mmp_{k}"] = d["ours"]["mmp"].get(f"{k}_critical_power")
        yield row


def load_rides(jsonl: Path) -> pd.DataFrame:
    df = pd.DataFrame(list(_ride_rows(jsonl)))
    for k in MMP_KEYS:
        col = f"mmp_{k}"
        df[col] = pd.to_numeric(df[col], errors="coerce")
        df.loc[df[col] <= 0, col] = np.nan  # GoldenCheetah's "no value"
    return df.sort_values(["athlete", "date"]).reset_index(drop=True)


def reference_dates(dates: pd.Series) -> list[pd.Timestamp]:
    """First ride date, then the first ride at least STEP_DAYS after the previous reference."""
    refs: list[pd.Timestamp] = []
    nxt = None
    for d in dates:
        if nxt is None or d >= nxt:
            refs.append(d)
            nxt = d + pd.Timedelta(days=STEP_DAYS)
    return refs


def build_units(rides: pd.DataFrame, athletes: dict[str, dict], splits: dict[str, str]) -> pd.DataFrame:
    units = []
    for athlete, g in rides.groupby("athlete", sort=False):
        g = g.sort_values("date")
        meta = athletes.get(athlete, {})
        for ref in reference_dates(g["date"]):
            w = g[(g["date"] > ref - pd.Timedelta(days=WINDOW_DAYS)) & (g["date"] <= ref)]
            if len(w) < MIN_RIDES:
                continue
            last = w.iloc[-1]
            label = last["thr"] if pd.notna(last["thr"]) else (last["cp"] if pd.notna(last["cp"]) else None)
            if label is None or not (LABEL_MIN <= label <= LABEL_MAX):
                continue
            # Fresh = the athlete's FTP label changed within +-FRESH_DAYS of the reference date, i.e.
            # some ride in that span carries a different label (rule as registered; the first draft
            # compared the values at -28 d and after +28 d instead, Codex review 3, 2026-10-10).
            near = g[
                (g["date"] >= ref - pd.Timedelta(days=FRESH_DAYS))
                & (g["date"] <= ref + pd.Timedelta(days=FRESH_DAYS))
            ]
            near_labels = near["thr"].where(near["thr"].notna(), near["cp"]).dropna()
            fresh = bool((near_labels != label).any())
            weights = w["weight"].dropna()
            yob = meta.get("yob")
            unit = {
                "athlete": athlete,
                "split": splits.get(athlete, "unknown"),
                "ref_date": ref.date().isoformat(),
                "label_ftp": float(label),
                "label_cp": float(last["cp"]) if pd.notna(last["cp"]) else np.nan,
                "fresh": bool(fresh),
                "n_rides": int(len(w)),
                "hours": float(w["hours"].sum()),
                "np_mean": float(w["np"].mean()),
                "np_max": float(w["np"].max()),
                "days_since_last": float((ref - w["date"].iloc[-1]).days),
                "gender_m": 1.0
                if meta.get("gender") == "M"
                else (0.0 if meta.get("gender") == "F" else np.nan),
                "age": float(ref.year - int(yob)) if yob and str(yob).isdigit() else np.nan,
                "weight": float(weights.iloc[-1]) if not weights.empty else np.nan,
            }
            for k in MMP_KEYS:
                unit[f"mmp_{k}"] = float(w[f"mmp_{k}"].max()) if w[f"mmp_{k}"].notna().any() else np.nan
            units.append(unit)
    return pd.DataFrame(units)


def cmd_build(features: Path, index_dir: Path, sample: Path, out: Path) -> None:
    rides = load_rides(features)
    splits = {r["id"]: r["split"] for r in json.loads(sample.read_text(encoding="utf-8"))}
    athletes = {}
    for aid in rides["athlete"].unique():
        f = index_dir / f"{aid}.json"
        if f.exists():
            d = json.loads(f.read_text(encoding="utf-8"))
            athletes[aid] = {"gender": d.get("gender"), "yob": d.get("yob")}
    units = build_units(rides, athletes, splits)
    out.parent.mkdir(parents=True, exist_ok=True)
    units.to_parquet(out, index=False)
    summary = {
        "rides": int(len(rides)),
        "athletes": int(rides["athlete"].nunique()),
        "units": int(len(units)),
        "by_split": {k: int(v) for k, v in units["split"].value_counts().items()},
        "fresh": int(units["fresh"].sum()),
        "with_20m": int(units["mmp_20m"].notna().sum()),
        "with_weight": int(units["weight"].notna().sum()),
    }
    out.with_suffix(".summary.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print(
        f"rides={len(rides)} athletes={rides['athlete'].nunique()} units={len(units)} "
        f"by_split={units['split'].value_counts().to_dict()} fresh={int(units['fresh'].sum())} "
        f"with_20m={int(units['mmp_20m'].notna().sum())} with_weight={int(units['weight'].notna().sum())}"
    )


# ----------------------------------------------------------------------------- candidates

FEATURES = [f"mmp_{k}" for k in MMP_KEYS] + [
    "n_rides",
    "hours",
    "np_mean",
    "np_max",
    "days_since_last",
    "gender_m",
    "age",
]


def rule_r1(u: pd.DataFrame) -> pd.Series:
    return 0.95 * u["mmp_20m"]


def rule_r2(u: pd.DataFrame) -> pd.Series:
    return u["mmp_60m"]


def rule_r3(u: pd.DataFrame) -> pd.Series:
    """Critical power from the 2-parameter model: work = CP × t + W' over the 3–20 min bests."""
    out = np.full(len(u), np.nan)
    t = np.array(list(CP_FIT_KEYS.values()), dtype=float)
    p = u[[f"mmp_{k}" for k in CP_FIT_KEYS]].to_numpy(dtype=float)
    for i in range(len(u)):
        ok = np.isfinite(p[i])
        if ok.sum() < 3:
            continue
        work = p[i][ok] * t[ok]
        slope, _ = np.polyfit(t[ok], work, 1)
        out[i] = slope
    return pd.Series(out, index=u.index)


class Candidate:
    """A rule (no fitting) or a model (fitted on train). ``min_train`` is 0 for rules."""

    def __init__(self, name: str, needs_weight: bool = False, min_train: int = 50):
        self.name = name
        self.needs_weight = needs_weight
        self.min_train = min_train

    def fit(self, train: pd.DataFrame) -> None:  # noqa: B027 - rules have nothing to fit
        pass

    def predict(self, u: pd.DataFrame) -> pd.Series:
        raise NotImplementedError


class RuleCandidate(Candidate):
    def __init__(self, name, fn):
        super().__init__(name, min_train=0)
        self.fn = fn

    def predict(self, u):
        return self.fn(u)


class CalibratedR1(Candidate):
    def __init__(self):
        super().__init__("R5")
        self.a, self.b = 1.0, 0.0

    def fit(self, train):
        x = rule_r1(train)
        ok = x.notna()
        self.a, self.b = np.polyfit(x[ok], train.loc[ok, "label_ftp"], 1)

    def predict(self, u):
        return self.a * rule_r1(u) + self.b


def _matrix(u: pd.DataFrame, with_weight: bool) -> pd.DataFrame:
    x = u[FEATURES].copy()
    if with_weight:
        for k in ("5m", "20m", "60m"):
            x[f"wkg_{k}"] = u[f"mmp_{k}"] / u["weight"]
        x["weight"] = u["weight"]
    return x


class LgbmCandidate(Candidate):
    def __init__(self, name="M1", with_weight=False):
        super().__init__(name, needs_weight=with_weight)
        self.with_weight = with_weight
        self.model = None

    def fit(self, train):
        import lightgbm as lgb

        self.model = lgb.LGBMRegressor(**LGBM_PARAMS)
        self.model.fit(_matrix(train, self.with_weight), train["label_ftp"])

    def predict(self, u):
        return pd.Series(self.model.predict(_matrix(u, self.with_weight)), index=u.index)


class RidgeCandidate(Candidate):
    def __init__(self):
        super().__init__("M2")
        self.pipe = None
        self.medians = None

    def _x(self, u):
        x = _matrix(u, False)
        for k in MMP_KEYS:
            x[f"mmp_{k}"] = np.log(x[f"mmp_{k}"].clip(lower=1))
        return x

    def fit(self, train):
        from sklearn.linear_model import Ridge
        from sklearn.pipeline import make_pipeline
        from sklearn.preprocessing import StandardScaler

        x = self._x(train)
        self.medians = x.median()
        self.pipe = make_pipeline(StandardScaler(), Ridge(alpha=1.0))
        self.pipe.fit(x.fillna(self.medians), train["label_ftp"])

    def predict(self, u):
        return pd.Series(self.pipe.predict(self._x(u).fillna(self.medians)), index=u.index)


def candidates() -> list[Candidate]:
    return [
        RuleCandidate("R1", rule_r1),
        RuleCandidate("R2", rule_r2),
        RuleCandidate("R3", rule_r3),
        CalibratedR1(),
        LgbmCandidate("M1"),
        LgbmCandidate("M1b", with_weight=True),
        RidgeCandidate(),
    ]


# ----------------------------------------------------------------------------- evaluation


def athlete_bootstrap(errors: pd.Series, athletes: pd.Series, stat, n=BOOTSTRAP_N, seed=SEED) -> dict:
    """Resample athletes with replacement, then compute ``stat`` over their pooled errors."""
    groups = {a: e.to_numpy() for a, e in errors.groupby(athletes)}
    keys = list(groups)
    if not keys:
        return {"point": None, "ci": [None, None], "n_units": 0, "n_athletes": 0}
    rng = np.random.default_rng(seed)
    vals = []
    for _ in range(n):
        pick = rng.integers(0, len(keys), len(keys))
        vals.append(stat(np.concatenate([groups[keys[i]] for i in pick])))
    return {
        "point": float(stat(errors.to_numpy())),
        "ci": [float(np.quantile(vals, 0.025)), float(np.quantile(vals, 0.975))],
        "n_units": int(len(errors)),
        "n_athletes": len(keys),
    }


def summarize(pred: pd.Series, u: pd.DataFrame, label: str = "label_ftp") -> dict:
    ok = pred.notna() & u[label].notna()
    err = pred[ok] - u.loc[ok, label]
    ath = u.loc[ok, "athlete"]
    return {
        "coverage": float(ok.mean()) if len(ok) else 0.0,
        "mae": athlete_bootstrap(err.abs(), ath, np.mean),
        "median_ae": athlete_bootstrap(err.abs(), ath, np.median),
        "mape": athlete_bootstrap((err.abs() / u.loc[ok, label]), ath, np.mean),
        "bias": athlete_bootstrap(err, ath, np.mean),
    }


def paired_mae_difference(a: pd.Series, b: pd.Series, u: pd.DataFrame, n=BOOTSTRAP_N, seed=SEED) -> dict:
    """MAE(a) − MAE(b) on the units where both predict, athlete-paired bootstrap."""
    ok = a.notna() & b.notna()
    d = (a[ok] - u.loc[ok, "label_ftp"]).abs() - (b[ok] - u.loc[ok, "label_ftp"]).abs()
    return athlete_bootstrap(d, u.loc[ok, "athlete"], np.mean, n, seed)


def evaluate(units: pd.DataFrame, split: str, adopt: str | None) -> dict:
    train = units[units["split"] == "train"]
    target = units[units["split"] == split]
    train = train[train["mmp_20m"].notna()]
    target = target[target["mmp_20m"].notna()]
    results = {
        "split": split,
        "n_train": int(len(train)),
        "n_units": int(len(target)),
        "n_athletes": int(target["athlete"].nunique()),
        "methods": {},
    }
    preds: dict[str, pd.Series] = {}
    for c in candidates():
        tr = train[train["weight"].notna()] if c.needs_weight else train
        tg = target[target["weight"].notna()] if c.needs_weight else target
        if len(tr) < c.min_train or len(tg) == 0:
            results["methods"][c.name] = {"skipped": f"train={len(tr)} target={len(tg)}"}
            continue
        c.fit(tr)
        p = c.predict(tg).reindex(target.index)
        preds[c.name] = p
        r = summarize(p, target)
        r["mae_vs_label_cp"] = summarize(p, target, "label_cp")["mae"]
        r["mae_fresh"] = summarize(p[target["fresh"]], target[target["fresh"]])["mae"]
        r["mae_stale"] = summarize(p[~target["fresh"]], target[~target["fresh"]])["mae"]
        results["methods"][c.name] = r
    if "R1" in preds:
        for name, p in preds.items():
            if name != "R1":
                results["methods"][name]["paired_mae_minus_r1"] = paired_mae_difference(
                    p, preds["R1"], target
                )
    results["fresh_units"] = int(target["fresh"].sum())
    results["label_cp_differs"] = int(((target["label_cp"] - target["label_ftp"]).abs() > 1).sum())
    results["label_cp_missing"] = int(target["label_cp"].isna().sum())
    if adopt and adopt in preds:
        diff = results["methods"][adopt].get("paired_mae_minus_r1")
        results["H5"] = {"adopted": adopt, "diff": diff, "verdict": _verdict_le(diff, -5.0)}
        fresh = results["methods"][adopt]["mae_fresh"]["point"]
        stale = results["methods"][adopt]["mae_stale"]["point"]
        results["H7"] = {
            "adopted": adopt,
            "mae_fresh": fresh,
            "mae_stale": stale,
            **_h7(target, preds[adopt]),
        }
        results["breakdown"] = {
            name: breakdown(preds[name], target) for name in ("R1", adopt) if name in preds
        }
    if "R1" in preds:
        bias = results["methods"]["R1"]["bias"]
        results["H6"] = {
            "bias": bias,
            "verdict": "지지"
            if bias["ci"][0] is not None and bias["ci"][0] > 0
            else ("기각" if bias["ci"][1] is not None and bias["ci"][1] <= 0 else "판정 불가"),
        }
    return results


def _verdict_le(diff: dict | None, threshold: float) -> str:
    """Supported when the CI upper bound is at most ``threshold`` (difference clearly below it)."""
    if not diff or diff["ci"][0] is None:
        return "판정 불가"
    lo, hi = diff["ci"]
    if hi <= threshold:
        return "지지"
    if lo > threshold:
        return "기각"
    return "판정 불가"


def _h7(target: pd.DataFrame, p: pd.Series, n=BOOTSTRAP_N, seed=SEED) -> dict:
    """Stale minus fresh MAE with independent athlete resampling; returns the interval and verdict."""
    err = (p - target["label_ftp"]).abs()
    fresh = err[target["fresh"] & err.notna()]
    stale = err[~target["fresh"] & err.notna()]
    if len(fresh) < 20 or len(stale) < 20:
        return {"diff_stale_minus_fresh": None, "verdict": "판정 불가"}
    rng = np.random.default_rng(seed)
    fa = target.loc[fresh.index, "athlete"]
    sa = target.loc[stale.index, "athlete"]
    gf = {a: e.to_numpy() for a, e in fresh.groupby(fa)}
    gs = {a: e.to_numpy() for a, e in stale.groupby(sa)}
    kf, ks = list(gf), list(gs)
    diffs = []
    for _ in range(n):
        f = np.concatenate([gf[kf[i]] for i in rng.integers(0, len(kf), len(kf))]).mean()
        s = np.concatenate([gs[ks[i]] for i in rng.integers(0, len(ks), len(ks))]).mean()
        diffs.append(s - f)
    lo, hi = float(np.quantile(diffs, 0.025)), float(np.quantile(diffs, 0.975))
    verdict = "지지" if lo >= 5 else ("기각" if hi < 5 else "판정 불가")
    return {
        "diff_stale_minus_fresh": {"point": float(stale.mean() - fresh.mean()), "ci": [lo, hi]},
        "verdict": verdict,
    }


def breakdown(pred: pd.Series, u: pd.DataFrame) -> dict:
    """MAE by gender, by decade of age and by rides in the window (reporting only)."""
    ok = pred.notna() & u["label_ftp"].notna()
    err = (pred[ok] - u.loc[ok, "label_ftp"]).abs()
    t = u.loc[ok].assign(err=err)
    gender = t["gender_m"].map({1.0: "M", 0.0: "F"}).fillna("unknown")
    decade = (t["age"] // 10 * 10).fillna(-1).astype(int).astype(str).replace("-1", "unknown")
    bucket = pd.cut(t["n_rides"], [0, 12, 20, 30, 10_000], labels=["8-12", "13-20", "21-30", "31+"])

    def groups(key):
        return {
            str(k): {"n": int(len(g)), "mae": float(g["err"].mean())}
            for k, g in t.groupby(key, observed=True)
        }

    return {"gender": groups(gender), "age_decade": groups(decade), "rides_in_window": groups(bucket)}


def to_markdown(r: dict) -> str:
    def ci(d):
        if not d or d.get("point") is None:
            return "-"
        return f"{d['point']:.1f} [{d['ci'][0]:.1f}, {d['ci'][1]:.1f}]"

    lines = [
        f"# FTP 추정 결과: {r['split']}",
        "",
        f"- 예측 단위 {r['n_units']}개(선수 {r['n_athletes']}명), train 단위 {r['n_train']}개, "
        f"신선 라벨 {r['fresh_units']}개",
        f"- L_CP가 L_FTP와 1 W 넘게 다른 단위 {r['label_cp_differs']}개, L_CP 없음 {r['label_cp_missing']}개",
        "",
        "| 방법 | 적용 | MAE (W) | 중앙값 AE | MAPE | 편향 | MAE-R1 (짝지음) | MAE 신선/묵은 | MAE vs L_CP |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for name, m in r["methods"].items():
        if "skipped" in m:
            lines.append(f"| {name} | 건너뜀 ({m['skipped']}) | | | | | | | |")
            continue
        mape = m["mape"]
        mape_s = f"{mape['point'] * 100:.1f}%" if mape.get("point") is not None else "-"
        cells = [
            name,
            f"{m['coverage'] * 100:.0f}%",
            ci(m["mae"]),
            ci(m["median_ae"]),
            mape_s,
            ci(m["bias"]),
            ci(m.get("paired_mae_minus_r1")),
            f"{ci(m['mae_fresh'])} / {ci(m['mae_stale'])}",
            ci(m["mae_vs_label_cp"]),
        ]
        lines.append("| " + " | ".join(cells) + " |")
    for h in ("H5", "H6", "H7"):
        if h in r:
            lines.append("")
            lines.append(f"- **{h}**: {json.dumps(r[h], ensure_ascii=False, default=str)}")
    if "breakdown" in r:
        lines += ["", "## 보고: 집단별 MAE (판정 없음)", ""]
        for name, b in r["breakdown"].items():
            for key, groups in b.items():
                cells = ", ".join(f"{k}: {v['mae']:.1f} W (n={v['n']})" for k, v in groups.items())
                lines.append(f"- {name} / {key}: {cells}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("build")
    s.add_argument("--features", type=Path, required=True)
    s.add_argument("--index", type=Path, required=True)
    s.add_argument("--sample", type=Path, required=True)
    s.add_argument("--out", type=Path, required=True)
    s = sub.add_parser("evaluate")
    s.add_argument("--units", type=Path, required=True)
    s.add_argument("--split", choices=["dev", "test"], required=True)
    s.add_argument("--adopt", default=None, help="candidate chosen on dev (required for the test run)")
    s.add_argument("--out", type=Path, required=True, help="output path without extension")
    a = p.parse_args(argv)
    if a.cmd == "build":
        cmd_build(a.features, a.index, a.sample, a.out)
    else:
        if a.split == "test" and not a.adopt:
            raise SystemExit("--adopt is required for the sealed test run (choose it on dev first)")
        units = pd.read_parquet(a.units)
        r = evaluate(units, a.split, a.adopt)
        a.out.parent.mkdir(parents=True, exist_ok=True)
        Path(f"{a.out}.json").write_text(
            json.dumps(r, indent=1, ensure_ascii=False, default=str), encoding="utf-8"
        )
        Path(f"{a.out}.md").write_text(to_markdown(r), encoding="utf-8")
        print(f"wrote {a.out}.json and {a.out}.md")


if __name__ == "__main__":
    main()
