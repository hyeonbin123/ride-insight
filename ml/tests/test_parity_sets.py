import json
from pathlib import Path

from ride_insight_ml import parity_sets


def _index(dir_: Path, aid: str, power_rides: int):
    rides = [{"csv": f"{i}.csv", "power_flag": True} for i in range(power_rides)]
    rides.append({"csv": None, "power_flag": True})
    rides.append({"csv": "x.csv", "power_flag": False})
    (dir_ / f"{aid}.json").write_text(json.dumps({"rides": rides}), encoding="utf-8")


def test_build_keeps_sample_order_and_threshold(tmp_path: Path):
    sample = []
    for i in range(400):
        split = "dev" if i % 10 == 0 else "train"
        sample.append({"id": f"a{i:03d}", "split": split})
        _index(tmp_path, f"a{i:03d}", 150 if i % 3 else 10)
    dev, sealed, info = parity_sets.build(sample, tmp_path, 100, allow_partial=False)
    assert len(dev) == 100 and len(sealed) == 100
    assert not set(dev) & set(sealed)
    assert dev[0] == "a001"  # a000 is a dev-split athlete, so it is not eligible
    assert all(int(x[1:]) % 10 != 0 and int(x[1:]) % 3 != 0 for x in dev + sealed)
    assert dev == sorted(dev)
    assert info["complete"]


def test_partial_stops_at_first_missing_index(tmp_path: Path):
    sample = [{"id": f"a{i}", "split": "train"} for i in range(10)]
    for i in range(4):
        _index(tmp_path, f"a{i}", 120)
    dev, sealed, info = parity_sets.build(sample, tmp_path, 100, allow_partial=True)
    assert dev == ["a0", "a1", "a2", "a3"] and sealed == []
    assert not info["complete"]
