"""GoldenCheetah OpenData (OSF osf.io/6hfpz, CC0 1.0) sampling, download and inventory.

Sub-commands (run as ``python -m ride_insight_ml.opendata <cmd>``):

* ``manifest``  list every file of the OSF project into a JSON manifest (OSF API v2)
* ``sample``    pick a deterministic athlete sample from the manifest (SHA-256 order of the id)
* ``download``  fetch the sampled zips with SHA-256 verification (skips verified files)
* ``inventory`` summarise each downloaded athlete (rides, power rides, intervals, cp_setting changes)
* ``split``     assign train/dev/test per athlete from the SHA-256 of the id

Nothing here resamples or cleans ride data; that lives in ``preprocess``.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import math
import re
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

OSF_FILES_URL = "https://api.osf.io/v2/nodes/6hfpz/files/osfstorage/?page%5Bsize%5D=100"
ATHLETE_ZIP_SUFFIX = ".zip"


def _sha256_of_id(athlete_id: str, domain: str) -> str:
    """SHA-256 of ``domain:id``.

    Sampling order and split assignment use different domains. With one shared hash, the first
    ``n`` ids in hash order would all fall into the lowest split bucket (found on 2026-10-10:
    800 sampled athletes were all 'train').
    """
    return hashlib.sha256(f"{domain}:{athlete_id}".encode()).hexdigest()


def athlete_id_from_name(name: str) -> str:
    """'033874ce-....zip' -> '033874ce-...'."""
    return name[: -len(ATHLETE_ZIP_SUFFIX)] if name.endswith(ATHLETE_ZIP_SUFFIX) else name


def split_of(athlete_id: str, train: float = 0.6, dev: float = 0.2) -> str:
    """Deterministic split from the first 4 bytes of SHA-256('split:' + id) mapped to [0, 1)."""
    u = int(_sha256_of_id(athlete_id, "split")[:8], 16) / 2**32
    if u < train:
        return "train"
    if u < train + dev:
        return "dev"
    return "test"


# ----------------------------------------------------------------------------- manifest


def _get_json(url: str, retries: int = 5) -> dict:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001 - retry any network error
            last = e
            time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"OSF request failed after {retries} attempts: {url}") from last


def cmd_manifest(out: Path) -> None:
    rows = []
    url: str | None = OSF_FILES_URL
    while url:
        d = _get_json(url)
        for x in d["data"]:
            a = x["attributes"]
            rows.append(
                {
                    "name": a["name"],
                    "size": a["size"],
                    "kind": a["kind"],
                    "download": x["links"].get("download"),
                    "sha256": (a.get("extra") or {}).get("hashes", {}).get("sha256"),
                    "modified": a.get("date_modified"),
                }
            )
        url = d["links"].get("next")
        print(f"rows={len(rows)}", file=sys.stderr)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, indent=0), encoding="utf-8")
    files = [r for r in rows if r["kind"] == "file"]
    print(f"files={len(files)} total_gb={sum(r['size'] for r in files) / 1e9:.1f}")


# ----------------------------------------------------------------------------- sample


def choose_sample(files: list[dict], n: int, max_bytes: int | None) -> list[dict]:
    """Take the first ``n`` athlete zips in SHA-256(id) order, skipping files above ``max_bytes``.

    The order is a fixed pseudo-random permutation, so the sample is reproducible and does not
    depend on upload order or file size (apart from the explicit cap).
    """
    zips = [f for f in files if f["kind"] == "file" and f["name"].endswith(ATHLETE_ZIP_SUFFIX)]
    if max_bytes is not None:
        zips = [f for f in zips if f["size"] <= max_bytes]
    zips.sort(key=lambda f: _sha256_of_id(athlete_id_from_name(f["name"]), "sample"))
    return zips[:n]


def cmd_sample(manifest: Path, n: int, max_bytes: int | None, out: Path) -> None:
    files = json.loads(manifest.read_text(encoding="utf-8"))
    picked = choose_sample(files, n, max_bytes)
    rows = [
        {
            "id": athlete_id_from_name(f["name"]),
            "name": f["name"],
            "size": f["size"],
            "sha256": f["sha256"],
            "download": f["download"],
            "split": split_of(athlete_id_from_name(f["name"])),
        }
        for f in picked
    ]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, indent=1), encoding="utf-8")
    by_split = collections.Counter(r["split"] for r in rows)
    print(f"picked={len(rows)} bytes={sum(r['size'] for r in rows) / 1e9:.2f}GB splits={dict(by_split)}")


# ----------------------------------------------------------------------------- download


def _file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cmd_download(sample: Path, dest: Path, limit: int | None) -> None:
    rows = json.loads(sample.read_text(encoding="utf-8"))
    dest.mkdir(parents=True, exist_ok=True)
    done = failed = skipped = 0
    for i, r in enumerate(rows):
        if limit is not None and i >= limit:
            break
        target = dest / r["name"]
        if target.exists():
            if _file_sha256(target) == r["sha256"]:
                skipped += 1
                continue
            # A file that fails verification must never be analysed: move it aside first
            # (Codex review 2026-10-10), then try to download a fresh copy.
            stale = target.with_suffix(".bad")
            target.replace(stale)
            print(f"stale file moved to {stale.name}", file=sys.stderr)
        tmp = target.with_suffix(".part")
        try:
            urllib.request.urlretrieve(r["download"], tmp)
            if _file_sha256(tmp) != r["sha256"]:
                tmp.unlink(missing_ok=True)
                failed += 1
                print(f"sha256 mismatch: {r['name']}", file=sys.stderr)
                continue
            tmp.replace(target)
            done += 1
            print(f"[{i + 1}/{len(rows)}] {r['name']} {r['size'] / 1e6:.1f}MB", file=sys.stderr)
        except Exception as e:  # noqa: BLE001
            tmp.unlink(missing_ok=True)
            failed += 1
            print(f"download failed: {r['name']}: {e}", file=sys.stderr)
    print(f"downloaded={done} skipped={skipped} failed={failed}")
    if failed:
        raise SystemExit(2)


# ----------------------------------------------------------------------------- inventory


_SPORT_LINE = re.compile(r'("sport":")(.*?)(",?\s*\n)')


def load_meta(raw: bytes) -> dict:
    """Parse an athlete's metadata JSON, repairing GoldenCheetah's unescaped quotes.

    One export (athlete 8fc5800d, 2026-10-10) contains ``"sport":""3.5-4.5 hours""``: the user-typed
    sport string was written without escaping. If strict parsing fails, inner double quotes on
    ``sport`` lines are replaced by single quotes and parsing is retried once.
    """
    text = raw.decode("utf-8", "replace")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        repaired = _SPORT_LINE.sub(lambda m: m.group(1) + m.group(2).replace('"', "'") + m.group(3), text)
        return json.loads(repaired)


def _metric(metrics: dict, key: str) -> float | None:
    v = metrics.get(key)
    if isinstance(v, list):
        v = v[0] if v else None
    if v in (None, "", "nan", "inf", "-inf"):
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def _interval_of_csv(text: str) -> float | None:
    lines = text.splitlines()
    if len(lines) < 3:
        return None
    try:
        a = float(lines[1].split(",")[0])
        b = float(lines[2].split(",")[0])
    except (ValueError, IndexError):
        return None
    return round(b - a, 2)


def inventory_of_zip(path: Path, csv_probe: int = 50) -> dict:
    """Summarise one athlete zip without extracting it."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        js = [n for n in names if n.endswith(".json")]
        meta = load_meta(z.read(js[0])) if js else {}
        rides = meta.get("RIDES", [])
        bike = [r for r in rides if (r.get("sport") or "") in ("Bike", "Turbo", "")]
        power = [r for r in bike if (_metric(r.get("METRICS", {}), "average_power") or 0) > 0]
        by_date = sorted(bike, key=lambda r: r.get("date") or "")
        cps = [_metric(r.get("METRICS", {}), "cp_setting") for r in by_date]
        cps = [c for c in cps if c]
        cp_changes = sum(1 for a, b in zip(cps, cps[1:], strict=False) if a != b)
        dates = sorted(r.get("date", "") for r in rides)
        csvs = [n for n in names if n.endswith(".csv")]
        intervals: collections.Counter[float] = collections.Counter()
        for n in csvs[:csv_probe]:
            iv = _interval_of_csv(z.read(n).decode("utf-8", "replace"))
            if iv is not None:
                intervals[iv] += 1
    athlete = meta.get("ATHLETE", {})
    return {
        "id": athlete_id_from_name(path.name),
        "size": path.stat().st_size,
        "gender": athlete.get("gender"),
        "yob": athlete.get("yob"),
        "rides": len(rides),
        "bike_rides": len(bike),
        "power_rides": len(power),
        "csv_files": len(csvs),
        "cp_values": len(set(cps)),
        "cp_changes": cp_changes,
        "cp_min": min(cps) if cps else None,
        "cp_max": max(cps) if cps else None,
        "first_date": dates[0] if dates else None,
        "last_date": dates[-1] if dates else None,
        "intervals_probe": {str(k): v for k, v in intervals.most_common(4)},
    }


def cmd_inventory(dest: Path, out: Path, sample: Path | None = None) -> None:
    """Summarise the zips in ``dest``; with ``sample`` only athletes listed in that manifest."""
    allowed = None
    if sample is not None:
        allowed = {r["id"] for r in json.loads(sample.read_text(encoding="utf-8"))}
    rows = []
    for p in sorted(dest.glob(f"*{ATHLETE_ZIP_SUFFIX}")):
        if allowed is not None and athlete_id_from_name(p.name) not in allowed:
            continue
        try:
            rows.append(inventory_of_zip(p))
        except (zipfile.BadZipFile, KeyError, json.JSONDecodeError) as e:
            rows.append({"id": athlete_id_from_name(p.name), "error": repr(e)})
            print(f"bad zip: {p.name}: {e!r}", file=sys.stderr)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, indent=1), encoding="utf-8")
    ok = [r for r in rows if "error" not in r]
    print(
        f"athletes={len(rows)} ok={len(ok)} power_rides_total={sum(r['power_rides'] for r in ok)} "
        f"with_cp_changes={sum(1 for r in ok if r['cp_changes'] > 0)}"
    )


# ----------------------------------------------------------------------------- split


def cmd_split(inventory: Path, min_power_rides: int, out: Path) -> None:
    rows = json.loads(inventory.read_text(encoding="utf-8"))
    kept = [r for r in rows if "error" not in r and r["power_rides"] >= min_power_rides]
    result = {
        "rule": f"power_rides >= {min_power_rides}; split = SHA-256('split:' + id) first 4 bytes / 2^32 "
        "in [0,0.6) train, [0.6,0.8) dev, [0.8,1) test",
        "athletes": {r["id"]: split_of(r["id"]) for r in kept},
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=1), encoding="utf-8")
    print(f"kept={len(kept)} {dict(collections.Counter(result['athletes'].values()))}")


# ----------------------------------------------------------------------------- cli


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("manifest", help="list OSF files into a JSON manifest")
    s.add_argument("--out", type=Path, required=True)

    s = sub.add_parser("sample", help="pick a deterministic athlete sample")
    s.add_argument("--manifest", type=Path, required=True)
    s.add_argument("--n", type=int, required=True)
    s.add_argument("--max-bytes", type=int, default=None)
    s.add_argument("--out", type=Path, required=True)

    s = sub.add_parser("download", help="download sampled zips with SHA-256 verification")
    s.add_argument("--sample", type=Path, required=True)
    s.add_argument("--dest", type=Path, required=True)
    s.add_argument("--limit", type=int, default=None)

    s = sub.add_parser("inventory", help="summarise downloaded athletes")
    s.add_argument("--dest", type=Path, required=True)
    s.add_argument("--out", type=Path, required=True)
    s.add_argument("--sample", type=Path, default=None, help="restrict to athletes in this sample manifest")

    s = sub.add_parser("split", help="assign train/dev/test per athlete")
    s.add_argument("--inventory", type=Path, required=True)
    s.add_argument("--min-power-rides", type=int, default=100)
    s.add_argument("--out", type=Path, required=True)

    a = p.parse_args(argv)
    if a.cmd == "manifest":
        cmd_manifest(a.out)
    elif a.cmd == "sample":
        cmd_sample(a.manifest, a.n, a.max_bytes, a.out)
    elif a.cmd == "download":
        cmd_download(a.sample, a.dest, a.limit)
    elif a.cmd == "inventory":
        cmd_inventory(a.dest, a.out, a.sample)
    elif a.cmd == "split":
        cmd_split(a.inventory, a.min_power_rides, a.out)


if __name__ == "__main__":
    main()
