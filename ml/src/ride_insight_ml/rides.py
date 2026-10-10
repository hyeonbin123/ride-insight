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

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import zipfile
from pathlib import Path

from ride_insight_ml.opendata import _metric, athlete_id_from_name

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
POWER_FLAG_INDEX = 3
MAX_OFFSET_SECS = 14 * 3600
OFFSET_STEP_SECS = 900


def parse_ride_date(text: str) -> dt.datetime:
    return dt.datetime.strptime(text.replace(" UTC", ""), "%Y/%m/%d %H:%M:%S")


def parse_csv_name(name: str) -> dt.datetime | None:
    try:
        return dt.datetime.strptime(name[:-4], "%Y_%m_%d_%H_%M_%S")
    except ValueError:
        return None


def has_power_flag(flags: str | None) -> bool:
    return bool(flags) and len(flags) > POWER_FLAG_INDEX and flags[POWER_FLAG_INDEX] == "P"


def last_secs_of_csv(text: str) -> float | None:
    lines = text.rstrip("\n").splitlines()
    if len(lines) < 2:
        return None
    try:
        return float(lines[-1].split(",")[0])
    except (ValueError, IndexError):
        return None


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
        if not flags.strip("-"):
            entry["match"] = "no_data"
            counters["no_data"] += 1
            entries.append(entry)
            continue
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


def index_zip(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        js = [n for n in names if n.endswith(".json")]
        meta = json.loads(z.read(js[0]).decode("utf-8", "replace")) if js else {}
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
