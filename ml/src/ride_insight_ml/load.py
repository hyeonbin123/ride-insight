"""Stage-5 load check (docs/experiments.md): concurrent FIT uploads and reads against a running server.

Usage: ``python -m ride_insight_ml.load --target http://127.0.0.1:8080 --fit <file.fit> --out <report.json>``

Scenario (pre-registered): 10 athletes are created; the same FIT file is uploaded to all 10 at once,
three rounds (every round a fresh set of athletes, because a repeat upload would be a 409); then 20
concurrent reads of ``samples?every=1`` and ``power-curve`` on the uploaded activities. Reports p50
and p95 latency per phase and the count of non-2xx responses. No verdict except "no 5xx".
"""

from __future__ import annotations

import argparse
import json
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib import request
from urllib.error import HTTPError

import numpy as np


def _req(
    method: str, url: str, body: bytes | None = None, content_type: str | None = None
) -> tuple[int, float, bytes]:
    req = request.Request(url, data=body, method=method)
    if content_type:
        req.add_header("Content-Type", content_type)
    t0 = time.perf_counter()
    try:
        with request.urlopen(req, timeout=120) as res:
            data = res.read()
            return res.status, time.perf_counter() - t0, data
    except HTTPError as e:
        return e.code, time.perf_counter() - t0, e.read()


def _multipart(name: str, payload: bytes) -> tuple[bytes, str]:
    boundary = f"----ri{uuid.uuid4().hex}"
    head = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    return head + payload + tail, f"multipart/form-data; boundary={boundary}"


def create_athletes(target: str, n: int) -> list[str]:
    ids = []
    for i in range(n):
        status, _, body = _req(
            "POST",
            f"{target}/api/athletes",
            json.dumps({"name": f"load {i}", "ftp": 250}).encode(),
            "application/json",
        )
        assert status == 201, (status, body[:200])
        ids.append(json.loads(body)["id"])
    return ids


def phase(name: str, calls: list, workers: int) -> dict:
    with ThreadPoolExecutor(max_workers=workers) as ex:
        results = list(ex.map(lambda c: c(), calls))
    lat = np.array([r[1] for r in results])
    statuses = [r[0] for r in results]
    return {
        "phase": name,
        "n": len(results),
        "concurrency": workers,
        "p50_ms": float(np.percentile(lat, 50) * 1000),
        "p95_ms": float(np.percentile(lat, 95) * 1000),
        "max_ms": float(lat.max() * 1000),
        "non_2xx": int(sum(1 for s in statuses if s // 100 != 2)),
        "status_counts": {str(s): statuses.count(s) for s in sorted(set(statuses))},
        "results": results,
    }


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--target", default="http://127.0.0.1:8080")
    p.add_argument("--fit", type=Path, required=True)
    p.add_argument("--rounds", type=int, default=3)
    p.add_argument("--uploads", type=int, default=10)
    p.add_argument("--reads", type=int, default=20)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args(argv)
    payload = a.fit.read_bytes()
    body, ctype = _multipart(a.fit.name, payload)

    phases = []
    activity_ids: list[str] = []
    for r in range(a.rounds):
        ids = create_athletes(a.target, a.uploads)
        calls = [
            (lambda i=i: _req("POST", f"{a.target}/api/athletes/{i}/activities", body, ctype)) for i in ids
        ]
        ph = phase(f"upload-round-{r + 1}", calls, a.uploads)
        for status, _, data in ph.pop("results"):
            if status == 201:
                activity_ids.append(json.loads(data)["id"])
        phases.append(ph)

    read_calls = []
    for i in range(a.reads):
        act = activity_ids[i % len(activity_ids)]
        path = (
            f"/api/activities/{act}/samples?every=1" if i % 2 == 0 else f"/api/activities/{act}/power-curve"
        )
        read_calls.append(lambda path=path: _req("GET", f"{a.target}{path}"))
    ph = phase("reads", read_calls, a.reads)
    ph.pop("results")
    phases.append(ph)

    report = {
        "target": a.target,
        "fit": a.fit.name,
        "fit_bytes": len(payload),
        "phases": phases,
        "any_5xx": any(int(s) >= 500 for ph in phases for s in ph["status_counts"]),
    }
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(report, indent=1), encoding="utf-8")
    for ph in phases:
        print(
            print(
                f"{ph['phase']}: n={ph['n']} p50={ph['p50_ms']:.0f}ms p95={ph['p95_ms']:.0f}ms "
                f"max={ph['max_ms']:.0f}ms statuses={ph['status_counts']}"
            )
        )
    print("any_5xx", report["any_5xx"])


if __name__ == "__main__":
    main()
