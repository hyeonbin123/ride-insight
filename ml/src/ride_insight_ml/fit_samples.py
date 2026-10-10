"""Download sample FIT files used by the server's parser tests.

Source: python-fitparse test suite (MIT licence), https://github.com/dtcooper/python-fitparse.
The files are not redistributed in this repository; run
``python -m ride_insight_ml.fit_samples --dest ../data/samples/fit``.
"""

from __future__ import annotations

import argparse
import sys
import urllib.request
from pathlib import Path

BASE = "https://raw.githubusercontent.com/dtcooper/python-fitparse/master/tests/files/"

# name -> why it is in the set
SAMPLES: dict[str, str] = {
    "garmin-edge-500-activity.fit": "Garmin Edge 500 outdoor ride",
    "Edge810-Vector-2013-08-16-15-35-10.fit": "Garmin Edge 810 with Vector power meter",
    "garmin-edge-820-bike.fit": "Garmin Edge 820 short ride",
    "2019-02-17-062644-ELEMNT-297E-195-0.fit": "Wahoo ELEMNT ride",
    "coros-pace-2-cycling-misaligned-fields.fit": "Coros Pace 2 ride with misaligned developer fields",
    "sample-activity-indoor-trainer.fit": "indoor trainer session",
    "garmin-fenix-5-run.fit": "running activity (should be rejected or flagged as non-cycling)",
    "activity-unexpected-eof.fit": "truncated file (must fail cleanly)",
    "activity-settings-corruptheader.fit": "corrupt header (must fail cleanly)",
}


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dest", type=Path, required=True)
    a = p.parse_args(argv)
    a.dest.mkdir(parents=True, exist_ok=True)
    for name in SAMPLES:
        target = a.dest / name
        if target.exists():
            continue
        urllib.request.urlretrieve(BASE + name, target)
        print(f"fetched {name}", file=sys.stderr)
    print(f"ok {len(SAMPLES)} files in {a.dest}")


if __name__ == "__main__":
    main()
