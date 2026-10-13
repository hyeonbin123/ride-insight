"""Match OpenData ride metadata to the CSV files inside an athlete zip and write a ride index.

The metadata ``date`` is UTC while CSV file names carry the device's local time, so names match
directly for only a minority of rides (203 of 731 for the first athlete inspected). A ride is
matched to the CSV whose timestamp differs by a multiple of 15 minutes within ±14 h **and**
whose last ``secs`` agrees with the ride's ``workout_time`` (within 60 s or 2 %). Rides with
several such candidates are left unmatched and counted as ambiguous.

The ``data`` field of a ride is a flag string such as ``TDSPHC-AGL-----``; ``P`` at index 3 means
the ride carries power samples.

Usage: ``python -m ride_insight_ml.rides --zips ../data/opendata --out ../data/opendata_index [--ids file]``
"""

# [파일 역할] 선수 zip 안의 라이딩 메타데이터(JSON)와 초 단위 CSV를 짝지어 선수별 '라이딩 인덱스' JSON을
# 만든다. 이 인덱스가 Kotlin parity 도구(어떤 CSV를 어떤 GC 값과 비교할지), parity_sets(선수 조건),
# ftp.py(성별·출생 연도)의 입력이다.
# [왜 필요한가] OpenData의 메타데이터 날짜는 UTC인데 CSV 파일 이름은 기기의 현지 시각이라, 이름이 그대로 맞는
# 라이딩은 일부(첫 선수 203/731)뿐이다. 그래서 시간대 차이를 허용하되 잘못 짝짓지 않도록 엄격한
# 규칙(docs/experiments.md 1단계 '라이딩↔CSV 매핑')을 둔다.
# [매핑 규칙] 파일 이름 시각 − UTC 날짜가 ±14시간 안의 15분 배수(세계 시간대 오프셋은 15분 단위)이고, CSV
# 마지막 secs가 workout_time과 60초 또는 2% 안에서 맞는 CSV가 정확히 하나일 때만 짝짓는다. 후보가 둘 이상이면
# '모호'로 두고 쓰지 않는다. 잘못 짝지은 CSV는 GC 값과의 비교를 그대로 오염시키므로 확실한 것만 쓴다.
# [보고] 매핑 결과(짝지음·모호·미매칭·데이터 없음 수)는 측정 결과와 함께 보고하는 것이 규칙이다. main이 선수
# 전체 합계를 출력한다.

from __future__ import annotations

# 표준 라이브러리만 쓴다. zipfile로 zip을 풀지 않고 안의 JSON·CSV를 바로 읽는다.
import argparse
import datetime as dt
import json
import math
import sys
import zipfile
from pathlib import Path

# opendata.py의 도우미를 재사용한다: GC 메트릭 값을 안전하게 숫자로 바꾸는 _metric, zip 이름 → 선수 id, 깨진
# GC JSON을 수리해 읽는 load_meta.
from ride_insight_ml.opendata import _metric, athlete_id_from_name, load_meta

# MMP_KEYS: GC가 라이딩마다 저장해 둔 16구간 최대 평균 파워 메트릭 이름(1초~90분). 파리티 비교의 GC 쪽 값이다.
MMP_KEYS = [
    "1s_critical_power",
    "5s_critical_power",
    "10s_critical_power",
    "15s_critical_power",
    "20s_critical_power",
    "30s_critical_power",
    "1m_critical_power",
    "2m_critical_power",
    "3m_critical_power",
    "5m_critical_power",
    "8m_critical_power",
    "10m_critical_power",
    "20m_critical_power",
    "30m_critical_power",
    "60m_critical_power",
    "90m_critical_power",
]
# GC_KEYS: 인덱스에 옮겨 둘 GC 메트릭. cp_setting(그날의 CP 설정 = 라벨 후보), coggan_np/if/tss(비교 대상),
# workout_time(매핑 검증), athlete_weight(FTP 특징) 등. 인덱스에 미리 옮겨 두면 이후 도구가 zip 안의 큰 JSON을
# 다시 읽지 않아도 된다.
GC_KEYS = [
    "cp_setting",
    "coggan_np",
    "coggan_if",
    "coggan_tss",
    "average_power",
    "workout_time",
    "time_riding",
    "total_distance",
    "athlete_weight",
    "max_power",
    *MMP_KEYS,
]
# POWER_FLAG_INDEX: 라이딩 data 플래그 문자열(예: TDSPHC-AGL-----)에서 4번째 글자(인덱스 3)가 'P'면 파워
# 시계열이 있다.
# MAX_OFFSET_SECS·OFFSET_STEP_SECS: UTC와 현지 시각 차이로 허용하는 범위(±14시간)와 단위(15분 = 900초).
POWER_FLAG_INDEX = 3
MAX_OFFSET_SECS = 14 * 3600
OFFSET_STEP_SECS = 900


# 메타데이터 날짜 '2020/01/31 07:00:00 UTC'를 시간대 없는 datetime(UTC 기준)으로 읽는다.
def parse_ride_date(text: str) -> dt.datetime:
    return dt.datetime.strptime(text.replace(" UTC", ""), "%Y/%m/%d %H:%M:%S")


# CSV 파일 이름 '2020_01_31_08_00_00.csv'(기기 현지 시각)를 datetime으로 읽는다. 형식이 다르면 None이라 매핑
# 후보에서 빠진다.
def parse_csv_name(name: str) -> dt.datetime | None:
    try:
        return dt.datetime.strptime(name[:-4], "%Y_%m_%d_%H_%M_%S")
    except ValueError:
        return None


# data 플래그에 파워 표시('P')가 있는지. 플래그가 비었거나 짧으면 False.
def has_power_flag(flags: str | None) -> bool:
    return bool(flags) and len(flags) > POWER_FLAG_INDEX and flags[POWER_FLAG_INDEX] == "P"


# CSV의 마지막 데이터 행의 secs(라이딩 길이)를 돌려준다. 매핑 검증에서 workout_time과 비교하는 값이다. 헤더만
# 있거나(행 2개 미만) 숫자가 아니거나 유한하지 않으면 None. splitlines()가 모든 줄의 목록을 한 번 만든다(예전
# docstring의 'without building a list'는 틀린 설명이라 고쳤다).
def last_secs_of_csv(text: str) -> float | None:
    """``secs`` of the last non-empty data row; None for a header-only file or a non-finite value."""
    last = None
    rows = 0
    for line in text.splitlines():
        if line.strip():
            last = line
            rows += 1
    if last is None or rows < 2:
        return None
    try:
        value = float(last.split(",")[0])
    except (ValueError, IndexError):
        return None
    return value if math.isfinite(value) else None


# 한 선수의 라이딩 목록을 CSV와 짝짓는다. 입력: 메타데이터 RIDES, CSV 이름 → 마지막 secs. 출력: 라이딩마다
# 항목(date, sport, flags, power_flag, csv, offset_secs, match, gc 값)과 집계(matched, ambiguous, unmatched,
# no_data). 호출 위치: index_zip, tests/test_rides.py.
def match_rides(rides: list[dict], csv_last_secs: dict[str, float | None]) -> tuple[list[dict], dict]:
    """Return (ride entries, counters). Each entry has ``csv`` (name or None) and ``match``."""
    csv_times = {n: t for n in csv_last_secs if (t := parse_csv_name(n)) is not None}
    entries: list[dict] = []
    counters = {"matched": 0, "ambiguous": 0, "unmatched": 0, "no_data": 0}
    used: set[str] = set()
    for r in rides:
        metrics = r.get("METRICS", {})
        flags = r.get("data") or ""
        date = parse_ride_date(r["date"])
        workout_time = _metric(metrics, "workout_time")
        entry = {
            "date": r["date"],
            "sport": r.get("sport"),
            "flags": flags,
            "power_flag": has_power_flag(flags),
            "csv": None,
            "offset_secs": None,
            "match": None,
            "gc": {k: _metric(metrics, k) for k in GC_KEYS},
        }
        # 플래그가 전부 '-'이면 시계열이 없는 라이딩(수동 입력 등)이라 CSV를 찾지 않는다.
        if not flags.strip("-"):
            entry["match"] = "no_data"
            counters["no_data"] += 1
            entries.append(entry)
            continue
        # 후보 조건 두 가지를 모두 만족하는 CSV만 모은다. (1) 파일 이름 시각과 UTC 날짜의 차이가 ±14시간
        # 안이고 15분의 배수(시간대 오프셋). (2) CSV 길이가 workout_time과 60초 또는 2% 중 큰 허용치 안.
        # 시각만 보면 하루 여러 번 탄 라이딩끼리 섞일 수 있어 길이로 한 번 더 거른다.
        candidates = []
        for name, t in csv_times.items():
            delta = (t - date).total_seconds()
            if abs(delta) > MAX_OFFSET_SECS or delta % OFFSET_STEP_SECS != 0:
                continue
            last = csv_last_secs.get(name)
            if workout_time is None or last is None:
                continue
            if abs(last - workout_time) <= max(60.0, 0.02 * workout_time):
                candidates.append((name, delta))
        # 후보가 정확히 하나일 때만 짝짓는다. 그 CSV를 이미 앞선 라이딩이 가져갔으면 같은 CSV가 두 라이딩에
        # 붙지 않도록 '모호'로 센다(먼저 처리된 라이딩의 짝은 유지된다).
        if len(candidates) == 1:
            name, delta = candidates[0]
            if name in used:
                entry["match"] = "ambiguous"
                counters["ambiguous"] += 1
            else:
                used.add(name)
                entry.update(csv=name, offset_secs=delta, match="matched")
                counters["matched"] += 1
        elif len(candidates) > 1:
            entry["match"] = "ambiguous"
            counters["ambiguous"] += 1
        else:
            entry["match"] = "unmatched"
            counters["unmatched"] += 1
        entries.append(entry)
    return entries, counters


# 선수 zip 하나를 인덱스 dict로 만든다. 메타데이터 JSON(첫 번째 .json)을 load_meta로 읽고, 모든 CSV의 마지막
# secs를 구한 뒤 match_rides로 짝짓는다. 출력에는 선수 id, 성별, 출생 연도, CSV 수, 집계, 라이딩 항목이
# 들어간다. 호출 위치: main.
def index_zip(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        js = [n for n in names if n.endswith(".json")]
        meta = load_meta(z.read(js[0])) if js else {}
        csv_last: dict[str, float | None] = {}
        for n in names:
            if n.endswith(".csv"):
                csv_last[n] = last_secs_of_csv(z.read(n).decode("utf-8", "replace"))
    entries, counters = match_rides(meta.get("RIDES", []), csv_last)
    athlete = meta.get("ATHLETE", {})
    return {
        "id": athlete_id_from_name(path.name),
        "gender": athlete.get("gender"),
        "yob": athlete.get("yob"),
        "csv_files": len(csv_last),
        "counters": counters,
        "rides": entries,
    }


# 명령행 진입점. --zips 폴더의 선수 zip마다 --out/<선수 id>.json 인덱스를 쓴다. --ids가 있으면 그 선수만
# 처리한다. 이미 인덱스가 있으면 다시 만들지 않고 읽어서 집계만 한다(긴 작업을 이어 돌릴 수 있게). 그래서 매핑
# 규칙을 바꾼 뒤에는 기존 인덱스를 지워야 새 규칙이 적용된다. 깨진 zip·JSON은 건너뛰고 표준 오류에 남긴다.
def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--zips", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--ids", type=Path, default=None, help="optional file with one athlete id per line")
    a = p.parse_args(argv)
    a.out.mkdir(parents=True, exist_ok=True)
    ids = None
    if a.ids:
        ids = {line.strip() for line in a.ids.read_text(encoding="utf-8").splitlines() if line.strip()}
    totals = {"matched": 0, "ambiguous": 0, "unmatched": 0, "no_data": 0}
    n = 0
    for path in sorted(a.zips.glob("*.zip")):
        aid = athlete_id_from_name(path.name)
        if ids is not None and aid not in ids:
            continue
        target = a.out / f"{aid}.json"
        if target.exists():
            idx = json.loads(target.read_text(encoding="utf-8"))
        else:
            try:
                idx = index_zip(path)
            except (zipfile.BadZipFile, KeyError, json.JSONDecodeError) as e:
                print(f"bad zip: {path.name}: {e!r}", file=sys.stderr)
                continue
            target.write_text(json.dumps(idx), encoding="utf-8")
        for k in totals:
            totals[k] += idx["counters"][k]
        n += 1
    print(f"athletes={n} {totals}")


if __name__ == "__main__":
    main()
