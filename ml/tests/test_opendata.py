# [테스트 묶음] opendata.py(OpenData 표본 추출·분할·인벤토리·메타데이터 읽기)의 단위 테스트. 네트워크 없이
# 가짜 파일 목록과 tmp_path에 만든 가짜 선수 zip으로 확인한다.
# [왜 중요한가] 표본과 분할은 이후 모든 측정의 바탕이라 여기서 틀리면 결과 전체가 무효가 된다. 실제로 표본
# 순서와 분할이 같은 해시를 써서 800명이 전부 train이 된 버그가 있었고(docs/ai-verification.md 0단계), Codex
# 1차 검토가 짚은 유한하지 않은 값·저장 순서·표본 밖 zip 문제도 각각 테스트로 고정했다.
# 뒤쪽 download 테스트는 urllib.request.urlopen과 time.sleep을 monkeypatch로 바꿔 네트워크와 실제 대기
# 없이 '멎은 연결 → 타임아웃 → 재시도 → 실패 집계 → 종료 코드 2' 경로와 해시 검증을 확인한다.

import hashlib
import io
import json
import zipfile
from pathlib import Path

import pytest

from ride_insight_ml import opendata


# 같은 id는 언제나 같은 분할을 받고(결정적), id 2,000개면 대략 60/20/20(넉넉한 허용 범위)으로 나뉘는지
# 확인한다.
def test_split_is_deterministic_and_covers_all_sets():
    ids = [f"athlete-{i}" for i in range(2000)]
    splits = [opendata.split_of(i) for i in ids]
    assert splits == [opendata.split_of(i) for i in ids]
    counts = {s: splits.count(s) for s in ("train", "dev", "test")}
    # id 2,000개에서 넉넉한 허용 범위 안의 60/20/20
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
    # 표본 순서와 분할은 해시를 같이 쓰면 안 된다. 같이 쓰면 앞에서부터 뽑은 표본이 전부 'train'이 된다.
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

    # 저장 순서가 1월 1일(200), 1월 3일(210), 1월 2일(200)로 뒤섞여 있다 -> 날짜순으로는 변경 1회.
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


# sample·inventory·split이 쓰는 JSON의 줄 끝이 LF인지 확인한다. newline 지정 없이 write_text로 쓰면
# Windows에서 CRLF가 되어, 같은 표본을 Linux에서 다시 만들면 내용이 같아도 파일 전체가 바뀐 것으로 보인다
# (저장소의 manifests/opendata_sample.json이 그렇게 CRLF로 커밋돼 있었다). Linux에서는 고치기 전에도 통과하고
# Windows에서만 고치기 전 코드가 실패한다.
def test_written_sample_inventory_and_split_use_lf_line_endings(tmp_path: Path):
    files = [
        {"name": f"{i:032x}.zip", "size": 1, "kind": "file", "sha256": "0", "download": "u"} for i in range(5)
    ]
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps(files), encoding="utf-8")
    sample = tmp_path / "sample.json"
    opendata.cmd_sample(manifest, 3, None, sample)
    rides = [{"date": "2020/01/01 10:00:00 UTC", "sport": "Bike", "METRICS": {"average_power": "150"}}]
    zips = tmp_path / "zips"
    zips.mkdir()
    _athlete_zip(zips, rides, {})
    inventory = tmp_path / "inventory.json"
    opendata.cmd_inventory(zips, inventory)
    split = tmp_path / "split.json"
    opendata.cmd_split(inventory, 0, split)
    for path in (sample, inventory, split):
        raw = path.read_bytes()
        assert b"\n" in raw, path.name
        assert b"\r" not in raw, path.name


# ----------------------------------------------------------------------------- download: 네트워크 견고성


# urlopen이 돌려주는 응답의 대역. read와 with 문은 io.BytesIO가 그대로 제공한다. info()는 빈 헤더를
# 돌려준다(예전 코드의 urlretrieve도 이 대역으로 끝까지 돌게 해, 고치기 전 실패가 대역의 빈틈이 아니라
# 타임아웃·재시도가 없어서임을 보이려는 것). stall_after를 주면 그만큼 읽은 뒤 다음 read에서
# TimeoutError(Python 3.10부터 socket.timeout과 같은 클래스)를 내 '받다가 멎은 연결'을 흉내 낸다.
class _FakeResponse(io.BytesIO):
    def __init__(self, data: bytes, stall_after: int | None = None):
        super().__init__(data)
        self._stall_after = stall_after

    def read(self, size: int | None = -1) -> bytes:
        if self._stall_after is not None and self.tell() >= self._stall_after:
            raise TimeoutError("timed out")
        return super().read(size)

    def info(self) -> dict:
        return {}


# 선수 zip 하나(abc.zip)만 든 표본 매니페스트를 쓴다. 기대 해시는 content의 SHA-256이다.
def _one_zip_sample(tmp_path: Path, content: bytes) -> Path:
    sample = tmp_path / "sample.json"
    row = {
        "id": "abc",
        "name": "abc.zip",
        "size": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
        "download": "https://files.osf.invalid/abc.zip",
    }
    sample.write_text(json.dumps([row]), encoding="utf-8")
    return sample


# 연결이 응답 없이 멎으면(TimeoutError) urlopen에 타임아웃 120초를 넘겨 매번 끊고, 5번까지 다시 시도한
# 뒤(사이 대기 5·10·15·20초, 마지막 실패 뒤에는 기다리지 않음) 그 파일을 failed로 세고 종료 코드 2로
# 끝나는지 확인한다. 예전 코드(urlretrieve)는 timeout을 넘기지 않아 실제 네트워크에서는 무한히 멈췄고, 이
# 대역에서는 한 번만 시도한 뒤(timeout 없음) 실패로 끝난다.
def test_download_times_out_retries_and_counts_a_stalled_file_as_failed(tmp_path, monkeypatch, capsys):
    sample = _one_zip_sample(tmp_path, b"zip bytes")
    timeouts: list = []
    waits: list = []

    def stalled(url, *args, **kwargs):
        timeouts.append(kwargs.get("timeout"))
        raise TimeoutError("timed out")

    monkeypatch.setattr(opendata.urllib.request, "urlopen", stalled)
    monkeypatch.setattr(opendata.time, "sleep", waits.append)
    dest = tmp_path / "dest"
    with pytest.raises(SystemExit) as e:
        opendata.cmd_download(sample, dest, None)
    assert e.value.code == 2
    assert timeouts == [120] * 5
    assert waits == [5, 10, 15, 20]
    out = capsys.readouterr()
    assert "downloaded=0 skipped=0 failed=1" in out.out
    assert "timed out" in out.err
    assert list(dest.iterdir()) == []


# 받는 도중(1 MiB 뒤) 멎은 첫 시도의 조각이 남지 않고, 두 번째 시도에서 처음부터 다시 받은 사본만 해시가
# 맞아 최종 이름으로 남는지 확인한다(2 MiB가 넘는 내용이라 1 MiB 단위 복사 고리를 여러 번 돈다).
def test_download_restarts_after_a_stall_mid_file_and_keeps_the_verified_copy(tmp_path, monkeypatch, capsys):
    content = bytes(range(256)) * 9000
    sample = _one_zip_sample(tmp_path, content)
    responses = [_FakeResponse(content, stall_after=1 << 20), _FakeResponse(content)]
    waits: list = []
    monkeypatch.setattr(opendata.urllib.request, "urlopen", lambda url, *a, **k: responses.pop(0))
    monkeypatch.setattr(opendata.time, "sleep", waits.append)
    dest = tmp_path / "dest"
    opendata.cmd_download(sample, dest, None)
    assert (dest / "abc.zip").read_bytes() == content
    assert [p.name for p in dest.iterdir()] == ["abc.zip"]
    assert waits == [5]
    assert "downloaded=1 skipped=0 failed=0" in capsys.readouterr().out


# 끝까지 받았지만 해시가 매니페스트와 다르면 .part를 지우고 최종 파일을 만들지 않으며, 다시 시도하지 않고
# (같은 내용이 또 올 가능성이 크다) failed로 세어 종료 코드 2로 끝나는지 확인한다. 고치기 전에도 같은
# 동작이었고, 해시를 받으면서 계산하도록 바꾼 뒤에도 검증이 빠지지 않았는지 지키는 특성화 테스트다.
def test_download_deletes_a_file_whose_hash_differs(tmp_path, monkeypatch, capsys):
    sample = _one_zip_sample(tmp_path, b"expected bytes")
    calls: list = []

    def tampered(url, *args, **kwargs):
        calls.append(url)
        return _FakeResponse(b"tampered bytes")

    monkeypatch.setattr(opendata.urllib.request, "urlopen", tampered)
    monkeypatch.setattr(opendata.time, "sleep", lambda s: None)
    dest = tmp_path / "dest"
    with pytest.raises(SystemExit) as e:
        opendata.cmd_download(sample, dest, None)
    assert e.value.code == 2
    assert calls == ["https://files.osf.invalid/abc.zip"]
    assert list(dest.iterdir()) == []
    assert "sha256 mismatch: abc.zip" in capsys.readouterr().err
