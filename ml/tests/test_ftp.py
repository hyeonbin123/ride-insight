import datetime as dt
import json
from pathlib import Path

import numpy as np
import pandas as pd

from ride_insight_ml import ftp


def _ride(athlete, day, ftp_w, np_w=None):
    """A ride whose mean-maximal powers follow a simple curve scaled by the athlete's FTP."""
    secs = {
        "1s": 1,
        "5s": 5,
        "10s": 10,
        "15s": 15,
        "20s": 20,
        "30s": 30,
        "1m": 60,
        "2m": 120,
        "3m": 180,
        "5m": 300,
        "8m": 480,
        "10m": 600,
        "20m": 1200,
        "30m": 1800,
        "60m": 3600,
        "90m": 5400,
    }
    mmp = {f"{k}_critical_power": ftp_w * (1 + 2.0 / (1 + t / 60)) for k, t in secs.items()}
    return {
        "athlete": athlete,
        "date": (dt.datetime(2020, 1, 1) + dt.timedelta(days=day)).strftime("%Y/%m/%d %H:%M:%S") + " UTC",
        "rec_int": 1.0,
        "samples": 3600,
        "cp": ftp_w + 10,
        "weight": 70.0,
        "ours": {"np": np_w or ftp_w * 0.8, "mmp": mmp},
        "gc": {"np": ftp_w * 0.8, "if": 0.8},
    }


def _write_fixture(tmp_path: Path, n_athletes=12):
    rows = []
    rng = np.random.default_rng(1)
    for i in range(n_athletes):
        base = 200 + 10 * i
        for day in range(0, 200, 3):
            w = base + (20 if day >= 100 else 0)  # FTP steps up on day 100
            rows.append(_ride(f"a{i}", day, w))
    jsonl = tmp_path / "all.jsonl"
    jsonl.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    index = tmp_path / "index"
    index.mkdir()
    for i in range(n_athletes):
        (index / f"a{i}.json").write_text(
            json.dumps({"gender": "M" if i % 2 else "F", "yob": "1985"}), encoding="utf-8"
        )
    sample = tmp_path / "sample.json"
    sample.write_text(
        json.dumps(
            [
                {"id": f"a{i}", "split": "train" if i < 8 else ("dev" if i < 10 else "test")}
                for i in range(n_athletes)
            ]
        ),
        encoding="utf-8",
    )
    del rng
    return jsonl, index, sample


def test_reference_dates_step_every_28_days():
    dates = pd.Series(pd.to_datetime(["2020-01-01", "2020-01-10", "2020-01-29", "2020-02-01", "2020-03-15"]))
    refs = ftp.reference_dates(dates)
    assert [d.date().isoformat() for d in refs] == ["2020-01-01", "2020-01-29", "2020-03-15"]


def test_build_units_labels_and_freshness(tmp_path: Path):
    jsonl, index, sample = _write_fixture(tmp_path)
    rides = ftp.load_rides(jsonl)
    splits = {r["id"]: r["split"] for r in json.loads(sample.read_text())}
    athletes = {f"a{i}": {"gender": "M", "yob": "1985"} for i in range(12)}
    units = ftp.build_units(rides, athletes, splits)
    assert len(units) > 0
    a0 = units[units["athlete"] == "a0"].sort_values("ref_date")
    # Label is NP / IF of the last ride in the window: 200 before day 100, 220 after.
    assert a0.iloc[0]["label_ftp"] == 200
    assert a0.iloc[-1]["label_ftp"] == 220
    assert a0.iloc[0]["label_cp"] == 210
    # The unit whose window ends right after the step is "fresh"; the first one is not.
    assert not a0.iloc[0]["fresh"]
    assert a0["fresh"].any()
    assert a0.iloc[0]["age"] == 35
    assert a0.iloc[0]["n_rides"] >= ftp.MIN_RIDES
    assert set(units["split"]) == {"train", "dev", "test"}


def test_freshness_follows_the_registered_definition(tmp_path: Path):
    # Label 250 until day 100, then 270. A unit is fresh only when a ride within +-28 days of its
    # reference date carries a different label.
    rows = [_ride("a", d, 250 if d < 100 else 270) for d in range(0, 200, 2)]
    jsonl = tmp_path / "r.jsonl"
    jsonl.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    rides = ftp.load_rides(jsonl)
    units = ftp.build_units(rides, {"a": {"gender": "M", "yob": "1990"}}, {"a": "train"})
    by_day = {
        int((pd.Timestamp(u.ref_date) - pd.Timestamp("2020-01-01")).days): bool(u.fresh)
        for u in units.itertuples()
    }
    assert by_day[84] is True  # change on day 100 is 16 days ahead
    assert by_day[28] is False  # change 72 days ahead
    assert by_day[112] is True  # change 12 days back
    assert by_day[168] is False


def test_rules_on_known_curve():
    u = pd.DataFrame(
        {
            "mmp_20m": [300.0, np.nan],
            "mmp_60m": [280.0, 250.0],
            "mmp_3m": [400.0, 400.0],
            "mmp_5m": [360.0, 360.0],
            "mmp_8m": [330.0, np.nan],
            "mmp_10m": [320.0, np.nan],
            "label_ftp": [285.0, 250.0],
            "athlete": ["a", "b"],
        }
    )
    assert ftp.rule_r1(u).iloc[0] == 285.0
    assert np.isnan(ftp.rule_r1(u).iloc[1])
    r3 = ftp.rule_r3(u)
    assert 250 < r3.iloc[0] < 320  # CP below the 20-min best, above the 60-min best here
    assert np.isnan(r3.iloc[1])  # fewer than three durations


def test_evaluate_runs_end_to_end(tmp_path: Path):
    jsonl, index, sample = _write_fixture(tmp_path)
    out = tmp_path / "units.parquet"
    ftp.cmd_build(jsonl, index, sample, out)
    units = pd.read_parquet(out)
    r = ftp.evaluate(units, "dev", adopt="R1")
    assert r["n_units"] > 0
    assert "R1" in r["methods"] and r["methods"]["R1"]["mae"]["point"] is not None
    # The synthetic curve has 20-min best = 1.095 × FTP, so 0.95 × that overestimates by ~4 %.
    assert r["methods"]["R1"]["bias"]["point"] > 0
    assert r["H6"]["verdict"] == "지지"
    md = ftp.to_markdown(r)
    assert "R1" in md and "H6" in md
    # LightGBM may be skipped with so little training data; whichever ran must have a coverage number.
    for m in r["methods"].values():
        assert "skipped" in m or 0 < m["coverage"] <= 1


def test_athlete_bootstrap_uses_athlete_resampling():
    err = pd.Series([1.0] * 50 + [9.0] * 50)
    ath = pd.Series(["a"] * 50 + ["b"] * 50)
    r = ftp.athlete_bootstrap(err, ath, np.mean, n=500)
    assert r["point"] == 5.0
    assert r["n_athletes"] == 2
    # With two athletes the resampled mean is 1, 5 or 9: the interval must reach both extremes.
    assert r["ci"][0] == 1.0 and r["ci"][1] == 9.0
