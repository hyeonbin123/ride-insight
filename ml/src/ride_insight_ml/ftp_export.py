"""Export a LightGBM FTP model as `dump_model()` JSON plus reference predictions for the Kotlin tests.

Usage:
  python -m ride_insight_ml.ftp_export --units <units.parquet> --variant M1
      --out-model <model.json> --out-cases <cases.json>
  python -m ride_insight_ml.ftp_export --synthetic --out-model ... --out-cases ...   (unit-test fixture)

The cases file holds up to 100 feature rows (dev split when available) with the Python prediction, so
`LightGbmModelTest` can check that the Kotlin tree walker reproduces them within 1e-6.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from ride_insight_ml import ftp


def _write(model, x: pd.DataFrame, out_model: Path, out_cases: Path, n_cases: int = 100) -> None:
    dump = model.booster_.dump_model()
    out_model.parent.mkdir(parents=True, exist_ok=True)
    out_model.write_text(json.dumps(dump), encoding="utf-8")
    sample = x.head(n_cases)
    preds = model.predict(sample)
    cases = []
    for (_, row), p in zip(sample.iterrows(), preds, strict=True):
        cases.append(
            {
                "features": {k: (None if pd.isna(v) else float(v)) for k, v in row.items()},
                "prediction": float(p),
            }
        )
    out_cases.parent.mkdir(parents=True, exist_ok=True)
    out_cases.write_text(json.dumps({"feature_names": list(x.columns), "cases": cases}), encoding="utf-8")
    print(f"trees={len(dump['tree_info'])} features={len(dump['feature_names'])} cases={len(cases)}")


def main(argv: list[str] | None = None) -> None:
    import lightgbm as lgb

    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--units", type=Path)
    p.add_argument("--variant", choices=["M1", "M1b"], default="M1")
    p.add_argument("--synthetic", action="store_true")
    p.add_argument("--out-model", type=Path, required=True)
    p.add_argument("--out-cases", type=Path, required=True)
    a = p.parse_args(argv)

    if a.synthetic:
        rng = np.random.default_rng(0)
        n = 400
        x = pd.DataFrame({"a": rng.normal(size=n), "b": rng.uniform(size=n), "c": rng.normal(size=n)})
        x.loc[rng.random(n) < 0.1, "c"] = np.nan  # missing values exercise the NaN branch
        y = 3 * x["a"] + 2 * x["b"] + x["c"].fillna(0) + rng.normal(scale=0.1, size=n)
        model = lgb.LGBMRegressor(
            n_estimators=30, num_leaves=7, learning_rate=0.2, min_child_samples=5, verbose=-1, random_state=0
        )
        model.fit(x, y)
        _write(model, x, a.out_model, a.out_cases)
        return

    units = pd.read_parquet(a.units)
    train = units[(units["split"] == "train") & units["mmp_20m"].notna()]
    with_weight = a.variant == "M1b"
    if with_weight:
        train = train[train["weight"].notna()]
    model = lgb.LGBMRegressor(**ftp.LGBM_PARAMS)
    xt = ftp._matrix(train, with_weight)
    model.fit(xt, train["label_ftp"])
    dev = units[(units["split"] == "dev") & units["mmp_20m"].notna()]
    if with_weight:
        dev = dev[dev["weight"].notna()]
    _write(model, ftp._matrix(dev, with_weight), a.out_model, a.out_cases)


if __name__ == "__main__":
    main()
