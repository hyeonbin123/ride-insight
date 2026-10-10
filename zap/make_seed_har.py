"""Write a HAR seed for the OWASP ZAP scan (zap/automation.yaml).

The seed carries the JSON requests ZAP cannot discover by spidering a JSON API: athlete creation,
the FTP threshold update and every read endpoint for one athlete and one activity. The multipart
FIT upload is not in the seed (HAR text bodies cannot carry the binary file); its robustness is
covered by the integration tests and the tampered-file tests instead (docs/experiments.md, 5단계).

Usage: python zap/make_seed_har.py --target http://127.0.0.1:8080 --athlete <id> --activity <id> --out work/zap/seed.har
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def entry(method: str, url: str, body: str | None = None, content_type: str = "application/json") -> dict:
    req = {
        "method": method,
        "url": url,
        "httpVersion": "HTTP/1.1",
        "cookies": [],
        "headers": [{"name": "Accept", "value": "application/json"}],
        "queryString": [],
        "headersSize": -1,
        "bodySize": -1,
    }
    if body is not None:
        req["headers"].append({"name": "Content-Type", "value": content_type})
        req["postData"] = {"mimeType": content_type, "text": body}
    return {
        "startedDateTime": "2026-10-10T00:00:00.000Z",
        "time": 1,
        "request": req,
        "response": {
            "status": 200,
            "statusText": "OK",
            "httpVersion": "HTTP/1.1",
            "cookies": [],
            "headers": [],
            "content": {"size": 0, "mimeType": "application/json"},
            "redirectURL": "",
            "headersSize": -1,
            "bodySize": -1,
        },
        "cache": {},
        "timings": {"send": 0, "wait": 1, "receive": 0},
    }


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--target", required=True)
    p.add_argument("--athlete", required=True)
    p.add_argument("--activity", required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args(argv)
    t = a.target.rstrip("/")
    ath = f"{t}/api/athletes/{a.athlete}"
    act = f"{t}/api/activities/{a.activity}"
    entries = [
        entry("GET", f"{t}/actuator/health"),
        entry("GET", ath),
        entry("GET", f"{ath}/thresholds"),
        entry("GET", f"{ath}/activities?limit=5"),
        entry("GET", f"{ath}/training-load?from=2019-01-01&to=2019-03-01"),
        entry("GET", f"{ath}/power-curve?weeks=6&asOf=2019-02-17"),
        entry("GET", act),
        entry("GET", f"{act}/samples?every=10"),
        entry("GET", f"{act}/power-curve"),
        entry("POST", f"{t}/api/athletes", json.dumps({"name": "zap seed", "gender": "M", "birthYear": 1990, "ftp": 250})),
        entry("PUT", f"{ath}/threshold", json.dumps({"effectiveFrom": "2019-01-01", "ftpWatts": 315})),
    ]
    har = {"log": {"version": "1.2", "creator": {"name": "ride-insight make_seed_har", "version": "1"}, "entries": entries}}
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(har, indent=1), encoding="utf-8")
    print(f"wrote {a.out} with {len(entries)} entries")


if __name__ == "__main__":
    main()
