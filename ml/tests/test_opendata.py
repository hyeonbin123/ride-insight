# [테스트 묶음] opendata.py(OpenData 표본 추출·분할·인벤토리·메타데이터 읽기)의 단위 테스트. 네트워크 없이
# 가짜 파일 목록과 tmp_path에 만든 가짜 선수 zip으로 확인한다.
# [왜 중요한가] 표본과 분할은 이후 모든 측정의 바탕이라 여기서 틀리면 결과 전체가 무효가 된다. 실제로 표본
# 순서와 분할이 같은 해시를 써서 800명이 전부 train이 된 버그가 있었고(docs/ai-verification.md 0단계), Codex
# 1차 검토가 짚은 유한하지 않은 값·저장 순서·표본 밖 zip 문제도 각각 테스트로 고정했다.

import json
import zipfile
from pathlib import Path

from ride_insight_ml import opendata


# 같은 id는 언제나 같은 분할을 받고(결정적), id 2,000개면 대략 60/20/20(넉넉한 허용 범위)으로 나뉘는지
# 확인한다.
def test_split_is_deterministic_and_covers_all_sets():
    ids = [f"athlete-{i}" for i in range(2000)]
    splits = [opendata.split_of(i) for i in ids]
    assert splits == [opendata.split_of(i) for i in ids]
    counts = {s: splits.count(s) for s in ("train", "dev", "test")}
    # 60/20/20 within a loose tolerance for 2000 ids
    assert 1100 < counts["train"] < 1300
    assert 300 < counts["dev"] < 500
    assert 300 < counts["test"] < 500


# 파일 목록의 순서를 뒤집어도 같은 표본이 뽑히고(해시 순서라 입력 순서와 무관), 크기 상한을 넘는 파일과 폴더
# 항목은 빠지는지 확인한다. 재현 가능한 표본이 목적이므로 순서 독립성이 핵심이다.
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


# 해시 순서 앞쪽 300명을 뽑았을 때 train·dev·test가 모두 나와야 한다. 표본 순서와 분할이 같은 해시를 쓰면
# 앞쪽이 전부 train이 되는 실제 버그의 회귀 테스트다.
def test_sampled_athletes_spread_over_all_splits():
    # Sampling order and split must not share a hash, otherwise a prefix sample is all 'train'.
    files = [{"name": f"{i:032x}.zip", "size": 1, "kind": "file"} for i in range(3000)]
    picked = opendata.choose_sample(files, 300, None)
    splits = {opendata.split_of(opendata.athlete_id_from_name(f["name"])) for f in picked}
    assert splits == {"train", "dev", "test"}


# GoldenCheetah OpenData 형식을 흉내 낸 선수 zip을 만든다: 메타데이터 JSON(VERSION, ATHLETE, RIDES) 하나와
# 주어진 CSV들. 파일 이름이 선수 id라 athlete_id_from_name 경로도 함께 시험된다.
def _athlete_zip(tmp_path: Path, rides: list[dict], csvs: dict[str, str]) -> Path:
    aid = "0123abcd-0000-0000-0000-000000000000"
    path = tmp_path / f"{aid}.zip"
    meta = {"VERSION": "1.8", "ATHLETE": {"gender": "F", "yob": "1990", "id": aid}, "RIDES": rides}
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(f"{{{aid}}}.json", json.dumps(meta))
        for name, text in csvs.items():
            z.writestr(name, text)
    return path


# 인벤토리 집계를 확인한다. 라이딩 4개 중 Run은 bike에서 빠지고(3개), 평균 파워가 'nan'인 라이딩은 파워
# 라이딩이 아니며, 목록 형식 값 ['180.0', '3600']은 첫 원소로 읽혀 파워 라이딩이 된다(2개). cp_setting 값
# 2종·변경 1회, 1초·5초 CSV의 간격 표본, 성별도 본다.
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


# GC 내보내기의 실제 결함('"sport":""3.5-4.5 hours""', 이스케이프되지 않은 따옴표)을 재현해 load_meta가 안쪽
# 큰따옴표를 작은따옴표로 바꿔 읽는지, 정상 JSON은 그대로 읽는지 확인한다. 이 수리가 없으면 해당 선수의 라이딩
# 전체를 잃는다.
def test_load_meta_repairs_unescaped_sport_quotes():
    raw = (
        b'{\n\t"VERSION":"1.8",\n\t"RIDES":[\n\t{\n\t\t"date":"2018/01/21 13:42:55 UTC",\n'
        b'\t\t"data":"TDSP-C-AGL-EV--",\n\t\t"sport":""3.5-4.5 hours"",\n\n\t\t"METRICS":{}\n\t}\n]}\n'
    )
    meta = opendata.load_meta(raw)
    assert meta["RIDES"][0]["sport"] == "'3.5-4.5 hours'"
    assert opendata.load_meta(b'{"a": 1}') == {"a": 1}


# 'Infinity', 넘치는 지수('1e309' → inf), 'nan'은 None이 되고 목록 값은 첫 원소를 쓰는지 확인한다.
# 'Infinity'가 양수 평균 파워로 인정되던 문제(Codex 1차 검토 지적 9)의 회귀 테스트다.
def test_metric_rejects_non_finite_values():
    assert opendata._metric({"average_power": "Infinity"}, "average_power") is None
    assert opendata._metric({"average_power": "1e309"}, "average_power") is None
    assert opendata._metric({"average_power": "nan"}, "average_power") is None
    assert opendata._metric({"average_power": ["180.5", "3600"]}, "average_power") == 180.5


# 라이딩이 날짜 순서와 다르게 저장돼 있어도 cp_setting 변경 횟수를 날짜순으로 세는지 확인한다. 저장 순서로
# 세면 200 → 210 → 200으로 2회가 되지만 날짜순으로는 200, 200, 210이라 1회다(Codex 1차 검토 지적 9).
def test_cp_changes_are_counted_in_date_order(tmp_path: Path):
    def ride(date, cp):
        return {"date": date, "sport": "Bike", "METRICS": {"average_power": "150", "cp_setting": cp}}

    # Stored out of order: Jan 1 (200), Jan 3 (210), Jan 2 (200) -> one change in time order.
    rides = [
        ride("2020/01/01 10:00:00 UTC", "200.00000"),
        ride("2020/01/03 10:00:00 UTC", "210.00000"),
        ride("2020/01/02 10:00:00 UTC", "200.00000"),
    ]
    inv = opendata.inventory_of_zip(_athlete_zip(tmp_path, rides, {}))
    assert inv["cp_changes"] == 1
    assert inv["cp_values"] == 2


# 폴더에 표본 밖 zip(ffffffff…)이 섞여 있을 때 --sample을 주면 매니페스트의 선수만 집계하고, 주지 않으면 둘 다
# 집계하는지 확인한다(Codex 1차 검토 지적 4).
def test_inventory_can_be_restricted_to_the_sample(tmp_path: Path):
    rides = [{"date": "2020/01/01 10:00:00 UTC", "sport": "Bike", "METRICS": {"average_power": "150"}}]
    first = _athlete_zip(tmp_path, rides, {})
    first.replace(tmp_path / "ffffffff-0000-0000-0000-000000000000.zip")
    _athlete_zip(tmp_path, rides, {})
    sample = tmp_path / "sample.json"
    sample.write_text(json.dumps([{"id": "0123abcd-0000-0000-0000-000000000000"}]), encoding="utf-8")
    out = tmp_path / "inv.json"
    opendata.cmd_inventory(tmp_path, out, sample)
    ids = [r["id"] for r in json.loads(out.read_text(encoding="utf-8"))]
    assert ids == ["0123abcd-0000-0000-0000-000000000000"]
    opendata.cmd_inventory(tmp_path, out, None)
    assert len(json.loads(out.read_text(encoding="utf-8"))) == 2
