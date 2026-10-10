import json
from pathlib import Path

import pandas as pd

from ride_insight_ml import parity

MMP_KEYS = ["1s_critical_power", "20m_critical_power"]


def _line(
    athlete, i, rec_int, np_ours, np_gc, tss_ours=50.0, tss_gc=50.0, mmp_gc_20m=250.0, mmp_ours_20m=250.0
):
    return {
        "athlete": athlete,
        "date": f"2020/01/{i + 1:02d} 10:00:00 UTC",
        "csv": f"2020_01_{i + 1:02d}_10_00_00.csv",
        "rec_int": rec_int,
        "samples": 3600,
        "last_secs": 3599.0,
        "power_missing": 0,
        "bad_rows": 0,
        "over_3000": 0,
        "negative": 0,
        "cp": 250.0,
        "ours": {
            "np": np_ours,
            "if": np_ours / 250.0,
            "tss": tss_ours,
            "mmp": {"1s_critical_power": 500.0, "20m_critical_power": mmp_ours_20m},
        },
        "gc": {
            "np": np_gc,
            "if": np_gc / 250.0,
            "tss": tss_gc,
            "average_power": 180.0,
            "workout_time": 3600.0,
            "mmp": {"1s_critical_power": 500.0, "20m_critical_power": mmp_gc_20m},
        },
    }


def test_agree_treats_absent_and_gc_zero_as_absent():
    ours = pd.Series([None, None, 100.0, 100.0, 100.0])
    theirs = pd.Series([None, 0.0, 100.5, 102.0, None])
    assert parity.agree(ours, theirs, 1.0).tolist() == [True, True, True, False, False]


def test_analysis_verdicts_on_synthetic_data(tmp_path: Path):
    lines = []
    # 300 one-second rides agreeing exactly -> H1/H2/H3 supported.
    for i in range(300):
        lines.append(_line(f"a{i % 10}", i % 28, 1.0, 200.0, 200.0))
    # 100 five-second rides, 40 % disagreeing by 3 W -> H4 (1 s rate higher by >= 5 pp) supported.
    for i in range(100):
        off = 3.0 if i % 5 < 2 else 0.0
        lines.append(_line(f"b{i % 5}", i % 28, 5.0, 200.0 + off, 200.0))
    jsonl = tmp_path / "dev.jsonl"
    jsonl.write_text("\n".join(json.dumps(x) for x in lines), encoding="utf-8")
    df = parity.load(jsonl)
    r = parity.analyse(df)
    assert r["rides"] == 400
    assert r["interval_groups"] == {"1s": 300, "5s": 100}
    assert r["H1_np_1s"]["verdict"] == "지지"
    assert r["H2_mmp_1s"]["verdict"] == "지지"
    assert r["H3_tss_1s"]["verdict"] == "지지"
    assert r["H4_np_other"]["rate"] == 0.6
    assert r["H4_np_other"]["verdict"] == "지지"
    md = parity.to_markdown(r, "dev")
    assert "H1 NP" in md and "지지" in md


def test_rejection_when_rate_is_clearly_low():
    lines = [_line("a", i % 28, 1.0, 200.0 + (2.0 if i % 2 else 0.0), 200.0) for i in range(200)]
    df = parity.load(_write(lines))
    r = parity.analyse(df)
    assert r["H1_np_1s"]["rate"] == 0.5
    assert r["H1_np_1s"]["verdict"] == "기각"


def _write(lines):
    import tempfile

    p = Path(tempfile.mkdtemp(dir=".pytest-tmp" if Path(".pytest-tmp").exists() else None)) / "x.jsonl"
    p.write_text("\n".join(json.dumps(x) for x in lines), encoding="utf-8")
    return p
