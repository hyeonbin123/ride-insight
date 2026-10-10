"""Decode sample FIT files with python-fitparse and write reference JSON for the Kotlin decoder tests.

Usage: ``python -m ride_insight_ml.fit_expected --fit <dir with .fit> --out <dir for .json>``
(the repository uses ``server/src/test/resources/fit`` and its ``expected/`` sub-folder).

For each ``*.fit``: ``file_id`` fields, ``sport``/``session`` summaries, every ``record`` message as
parallel arrays (timestamp as Unix seconds, power, heart_rate, cadence, distance m, altitude m,
speed m/s; ``null`` where the field is absent or invalid) and, for files fitparse rejects, the
exception type. Only fields the Kotlin decoder is expected to produce are written.
"""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path

import fitparse

RECORD_FIELDS = ["power", "heart_rate", "cadence", "distance", "speed", "altitude"]
SESSION_FIELDS = [
    "sport",
    "sub_sport",
    "start_time",
    "total_elapsed_time",
    "total_timer_time",
    "total_distance",
    "avg_power",
    "max_power",
    "normalized_power",
    "training_stress_score",
    "intensity_factor",
    "threshold_power",
    "total_ascent",
]
FILE_ID_FIELDS = ["type", "manufacturer", "product", "serial_number", "time_created"]


def _plain(v):
    if isinstance(v, datetime):
        return int(v.replace(tzinfo=UTC).timestamp()) if v.tzinfo is None else int(v.timestamp())
    if isinstance(v, float):
        return round(v, 6)
    return v


def _get(msg, name, prefer_enhanced=False):
    if prefer_enhanced:
        enhanced = msg.get_value(f"enhanced_{name}")
        if enhanced is not None:
            return _plain(enhanced)
    return _plain(msg.get_value(name))


def decode(path: Path) -> dict:
    out: dict = {"file": path.name}
    try:
        fit = fitparse.FitFile(str(path), check_crc=False)
        fit.parse()
    except Exception as e:  # noqa: BLE001 - we record the failure kind
        out["error"] = type(e).__name__
        out["error_message"] = str(e)[:200]
        return out
    file_ids = list(fit.get_messages("file_id"))
    if file_ids:
        out["file_id"] = {k: _plain(file_ids[0].get_value(k)) for k in FILE_ID_FIELDS}
    sessions = []
    for s in fit.get_messages("session"):
        sessions.append({k: _plain(s.get_value(k)) for k in SESSION_FIELDS})
    out["sessions"] = sessions
    out["sports"] = [{"sport": _plain(m.get_value("sport"))} for m in fit.get_messages("sport")]
    records = {k: [] for k in ["timestamp", *RECORD_FIELDS]}
    for r in fit.get_messages("record"):
        records["timestamp"].append(_get(r, "timestamp"))
        for k in RECORD_FIELDS:
            records[k].append(_get(r, k, prefer_enhanced=k in ("speed", "altitude")))
    out["records"] = records
    out["record_count"] = len(records["timestamp"])
    return out


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--fit", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args(argv)
    a.out.mkdir(parents=True, exist_ok=True)
    for path in sorted(a.fit.glob("*.fit")):
        result = decode(path)
        (a.out / f"{path.stem}.json").write_text(json.dumps(result, separators=(",", ":")), encoding="utf-8")
        print(
            path.name,
            result.get("error") or f"records={result['record_count']} sessions={len(result['sessions'])}",
        )


if __name__ == "__main__":
    main()
