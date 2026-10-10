"""Build the stage-1 parity athlete lists (docs/experiments.md).

Rule: walk ``manifests/opendata_sample.json`` in sample order, keep train athletes whose ride index
has at least ``--min-power-rides`` matched power rides; the first 100 are the development set and
the next 100 the sealed set. Athletes whose zip or index is missing stop the walk (``--allow-partial``
writes what is available so far and reports how many are still needed).

Usage: ``python -m ride_insight_ml.parity_sets --sample ../manifests/opendata_sample.json
--index ../data/opendata_index --out-dir ../manifests``
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

DEV_SIZE = 100
SEALED_SIZE = 100


def matched_power_rides(index_file: Path) -> int:
    d = json.loads(index_file.read_text(encoding="utf-8"))
    return sum(1 for r in d["rides"] if r["csv"] and r["power_flag"])


def build(
    sample: list[dict],
    index_dir: Path,
    min_power_rides: int,
    allow_partial: bool,
    unavailable: set[str] | None = None,
) -> tuple[list, list, dict]:
    """``unavailable``: athletes whose zip could not be downloaded after retries; they are skipped in
    sample order (recorded in the result), which cannot change the picks before them."""
    qualifying: list[str] = []
    seen = skipped = 0
    skipped_unavailable: list[str] = []
    for row in sample:
        if row["split"] != "train":
            continue
        idx = index_dir / f"{row['id']}.json"
        if not idx.exists():
            if unavailable and row["id"] in unavailable:
                skipped_unavailable.append(row["id"])
                continue
            if allow_partial:
                break
            raise FileNotFoundError(f"index missing for {row['id']} (download and index it first)")
        seen += 1
        if matched_power_rides(idx) >= min_power_rides:
            qualifying.append(row["id"])
        else:
            skipped += 1
        if len(qualifying) >= DEV_SIZE + SEALED_SIZE:
            break
    dev = qualifying[:DEV_SIZE]
    sealed = qualifying[DEV_SIZE : DEV_SIZE + SEALED_SIZE]
    info = {
        "train_seen": seen,
        "below_threshold": skipped,
        "qualifying": len(qualifying),
        "complete": len(qualifying) >= DEV_SIZE + SEALED_SIZE,
        "skipped_unavailable": skipped_unavailable,
    }
    return dev, sealed, info


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--sample", type=Path, required=True)
    p.add_argument("--index", type=Path, required=True)
    p.add_argument("--out-dir", type=Path, required=True)
    p.add_argument("--min-power-rides", type=int, default=100)
    p.add_argument("--allow-partial", action="store_true")
    p.add_argument(
        "--unavailable", type=Path, default=None, help="file with ids whose zip could not be downloaded"
    )
    a = p.parse_args(argv)
    sample = json.loads(a.sample.read_text(encoding="utf-8"))
    unavailable = None
    if a.unavailable:
        unavailable = {x.strip() for x in a.unavailable.read_text(encoding="utf-8").splitlines() if x.strip()}
    dev, sealed, info = build(sample, a.index, a.min_power_rides, a.allow_partial, unavailable)
    a.out_dir.mkdir(parents=True, exist_ok=True)
    (a.out_dir / "parity_dev.txt").write_text("\n".join(dev) + ("\n" if dev else ""), encoding="utf-8")
    (a.out_dir / "parity_sealed.txt").write_text(
        "\n".join(sealed) + ("\n" if sealed else ""), encoding="utf-8"
    )
    print(json.dumps({**info, "dev": len(dev), "sealed": len(sealed)}))


if __name__ == "__main__":
    main()
