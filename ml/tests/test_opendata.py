import json
import zipfile
from pathlib import Path

from ride_insight_ml import opendata


def test_split_is_deterministic_and_covers_all_sets():
    ids = [f"athlete-{i}" for i in range(2000)]
    splits = [opendata.split_of(i) for i in ids]
    assert splits == [opendata.split_of(i) for i in ids]
    counts = {s: splits.count(s) for s in ("train", "dev", "test")}
    # 60/20/20 within a loose tolerance for 2000 ids
    assert 1100 < counts["train"] < 1300
    assert 300 < counts["dev"] < 500
    assert 300 < counts["test"] < 500


def test_choose_sample_is_order_independent_and_respects_cap():
    files = [{"name": f"{i:032x}.zip", "size": 10 * i, "kind": "file"} for i in range(1, 50)] + [
        {"name": "folder", "size": 0, "kind": "folder"}
    ]
    a = opendata.choose_sample(files, 10, None)
    b = opendata.choose_sample(list(reversed(files)), 10, None)
    assert [f["name"] for f in a] == [f["name"] for f in b]
    assert len(a) == 10
    capped = opendata.choose_sample(files, 100, max_bytes=100)
    assert all(f["size"] <= 100 for f in capped)
    assert all(f["kind"] == "file" for f in capped)


def test_sampled_athletes_spread_over_all_splits():
    # Sampling order and split must not share a hash, otherwise a prefix sample is all 'train'.
    files = [{"name": f"{i:032x}.zip", "size": 1, "kind": "file"} for i in range(3000)]
    picked = opendata.choose_sample(files, 300, None)
    splits = {opendata.split_of(opendata.athlete_id_from_name(f["name"])) for f in picked}
    assert splits == {"train", "dev", "test"}


def _athlete_zip(tmp_path: Path, rides: list[dict], csvs: dict[str, str]) -> Path:
    aid = "0123abcd-0000-0000-0000-000000000000"
    path = tmp_path / f"{aid}.zip"
    meta = {"VERSION": "1.8", "ATHLETE": {"gender": "F", "yob": "1990", "id": aid}, "RIDES": rides}
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(f"{{{aid}}}.json", json.dumps(meta))
        for name, text in csvs.items():
            z.writestr(name, text)
    return path


def test_inventory_counts_power_rides_and_cp_changes(tmp_path: Path):
    def ride(date, sport, avg_power, cp):
        return {
            "date": date,
            "sport": sport,
            "METRICS": {"average_power": avg_power, "cp_setting": cp},
        }

    rides = [
        ride("2020/01/01 10:00:00 UTC", "Bike", "150.0", "200.00000"),
        ride("2020/01/02 10:00:00 UTC", "Bike", "nan", "200.00000"),
        ride("2020/01/03 10:00:00 UTC", "Run", "0.0", "200.00000"),
        ride("2020/02/01 10:00:00 UTC", "Bike", ["180.0", "3600"], "210.00000"),
    ]
    header = "secs,km,power,hr,cad,alt\n"
    one_second = header + "0,0,100,120,80,10\n1,0.01,110,121,81,10\n2,0.02,120,122,82,10\n"
    five_seconds = header + "0,0,100,120,80,10\n5,0.03,110,121,81,10\n10,0.06,120,122,82,10\n"
    csvs = {"2020_01_01_10_00_00.csv": one_second, "2020_02_01_10_00_00.csv": five_seconds}
    inv = opendata.inventory_of_zip(_athlete_zip(tmp_path, rides, csvs))
    assert inv["rides"] == 4
    assert inv["bike_rides"] == 3
    assert inv["power_rides"] == 2
    assert inv["cp_values"] == 2
    assert inv["cp_changes"] == 1
    assert inv["intervals_probe"] == {"1.0": 1, "5.0": 1}
    assert inv["gender"] == "F"
