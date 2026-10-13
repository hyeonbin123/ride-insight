"""GoldenCheetah OpenData (OSF osf.io/6hfpz, CC0 1.0) sampling, download and inventory.

Sub-commands (run as ``python -m ride_insight_ml.opendata <cmd>``):

* ``manifest``  list every file of the OSF project into a JSON manifest (OSF API v2)
* ``sample``    pick a deterministic athlete sample from the manifest (SHA-256 order of the id)
* ``download``  fetch the sampled zips with SHA-256 verification (skips verified files)
* ``inventory`` summarise each downloaded athlete (rides, power rides, intervals, cp_setting changes)
* ``split``     assign train/dev/test per athlete from the SHA-256 of the id

Nothing here resamples or cleans ride data: ``rides`` maps rides to their CSV files and the Kotlin
``parity`` tool (``OpenDataCsv``) reads and cleans the samples.
"""

# [파일 역할] GoldenCheetah OpenData(선수 6,613명, 115.6 GB)에서 이 프로젝트가 쓰는 표본 800명을 정하고,
# 내려받고, 요약하는 0단계 도구다. 결과물인 manifests/opendata_sample.json(선수 id, 파일 이름·크기·SHA-256,
# 다운로드 주소, 분할)이 이후 모든 단계(rides.py 인덱스, 파리티, FTP 실험)의 출발점이다.
# [설계 이유] 데이터는 재배포하지 않고(README 데이터 절) 표본 목록과 이 스크립트만 커밋한다. 그래서 누가
# 돌려도 같은 표본·같은 분할이 나와야 한다. 무작위 추출 대신 선수 id의 SHA-256 순서로 뽑고, 분할도 SHA-256으로
# 정해 seed나 실행 순서에 좌우되지 않게 했다. 표본 순서와 분할에 같은 해시를 썼다가 800명이 전부 train이 된
# 실수가 있어 용도 접두어(sample:, split:)로 분리했다(docs/ai-verification.md 0단계).
# [흐름] manifest(OSF API로 전체 파일 목록) → sample(80 MB 이하 중 해시 순서 앞 800명 + 분할) →
# download(SHA-256 검증) → inventory(선수별 라이딩·파워·cp_setting 요약) → split(파워 라이딩 수 조건으로
# 분할표).
# [연결] rides.py가 이 파일의 load_meta, _metric, athlete_id_from_name을 가져다 쓴다. 라이딩과 CSV 짝짓기는
# rides.py가, CSV 읽기·정리(깨진 행, 결측 파워)는 Kotlin parity 도구의 OpenDataCsv가 한다. docstring이 예전에
# 가리키던 'preprocess' 모듈은 만들지 않았다.

from __future__ import annotations

# 외부 의존성 없이 표준 라이브러리만 쓴다: OSF API 호출·다운로드(urllib), 해시(hashlib), zip 안의 파일을
# 압축을 풀지 않고 바로 읽기(zipfile; 표본 12.4 GB를 풀어 둘 필요가 없다), GC JSON 수리(re), 유한값
# 검사(math).
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

# OSF_FILES_URL: OSF API v2의 OpenData 노드(6hfpz) 파일 목록. page[size]=100으로 한 페이지에 100개씩
# 받는다(URL 인코딩된 %5B %5D가 대괄호다).
# ATHLETE_ZIP_SUFFIX: 선수 1명 = zip 1개이고 파일 이름이 '<선수 id>.zip'이다.
OSF_FILES_URL = "https://api.osf.io/v2/nodes/6hfpz/files/osfstorage/?page%5Bsize%5D=100"
ATHLETE_ZIP_SUFFIX = ".zip"


# '용도:id'의 SHA-256 16진 문자열. 용도(domain)가 'sample'이면 표본 순서, 'split'이면 분할에 쓴다. 같은 id라도
# 용도마다 서로 무관한 값이 나와 '표본 앞쪽 = 특정 분할' 같은 치우침이 없다.
def _sha256_of_id(athlete_id: str, domain: str) -> str:
    """SHA-256 of ``domain:id``.

    Sampling order and split assignment use different domains. With one shared hash, the first
    ``n`` ids in hash order would all fall into the lowest split bucket (found on 2026-10-10:
    800 sampled athletes were all 'train').
    """
    return hashlib.sha256(f"{domain}:{athlete_id}".encode()).hexdigest()


# zip 파일 이름에서 선수 id를 얻는다(.zip 접미사 제거). rides.py도 같은 규칙을 쓴다.
def athlete_id_from_name(name: str) -> str:
    """'033874ce-....zip' -> '033874ce-...'."""
    return name[: -len(ATHLETE_ZIP_SUFFIX)] if name.endswith(ATHLETE_ZIP_SUFFIX) else name


# 선수 id → train/dev/test. SHA-256('split:' + id)의 앞 8자리 16진수(4바이트)를 2^32로 나눠 [0, 1) 사이 수로
# 만들고, 0.6 미만 train, 0.8 미만 dev, 나머지 test로 나눈다(60/20/20, docs/experiments.md 공통 규칙).
# 선수 단위로 정하므로 같은 선수의 라이딩이 여러 분할에 섞이지 않는다. 결과가 id만으로 정해져 표본에 선수를
# 더하거나 빼도 기존 선수의 분할은 변하지 않는다. 호출 위치: cmd_sample, cmd_split, tests/test_opendata.py.
def split_of(athlete_id: str, train: float = 0.6, dev: float = 0.2) -> str:
    """Deterministic split from the first 4 bytes of SHA-256('split:' + id) mapped to [0, 1)."""
    u = int(_sha256_of_id(athlete_id, "split")[:8], 16) / 2**32
    if u < train:
        return "train"
    if u < train + dev:
        return "dev"
    return "test"


# ----------------------------------------------------------------------------- manifest


# URL에서 JSON을 받는다. 네트워크 오류면 5초, 10초, 15초 … 로 기다리는 시간을 늘려 가며 최대 5번 시도하고,
# 끝내 실패하면 마지막 예외를 원인으로 붙여 RuntimeError를 낸다. OSF API가 가끔 실패해도 수천 개 목록 수집이
# 중간에 끊기지 않게 하려는 것이다.
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


# manifest 하위 명령. OSF 파일 목록을 links.next가 없을 때까지 페이지를 넘기며 모두 모아 JSON으로 쓴다.
# 파일마다 이름, 크기, 종류(file/folder), 다운로드 주소, OSF가 계산해 둔 SHA-256, 수정 시각을 남긴다.
# SHA-256은 download 단계의 무결성 검증 기준이 된다.
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


# 선수 zip만 골라 max_bytes(계획: 80 MB) 이하로 거른 뒤 SHA-256('sample:' + id) 순서로 정렬해 앞 n개를 고른다.
# 입력 순서와 무관하게 같은 결과가 나오는지, 크기 상한을 지키는지를 tests/test_opendata.py가 확인한다. 이
# 상한으로 전체 115.6 GB 중 받을 양이 표본 800명 12.4 GB가 됐다(docs/plan.md).
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


# sample 하위 명령. 고른 선수마다 id, 파일 이름, 크기, SHA-256, 다운로드 주소, 분할을 적은 표본 매니페스트를
# 쓴다(저장소의 manifests/opendata_sample.json). 마지막에 분할별 인원을 출력한다. 같은 해시를 쓰던 버그(800명
# 전부 train)는 이런 분포 출력을 보고 발견했다(docs/ai-verification.md 0단계).
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


# 파일의 SHA-256. 1 MB(1 << 20)씩 읽어 수십 MB zip도 메모리에 통째로 올리지 않는다.
def _file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# download 하위 명령. 표본 매니페스트의 zip을 차례로 받는다(limit이 있으면 앞 limit명만).
# - 이미 있고 SHA-256이 맞으면 건너뛴다. 중간에 끊겨도 같은 명령을 다시 돌리면 남은 것만 받는다.
# - .part 임시 파일로 받은 뒤 해시가 맞을 때만 최종 이름으로 바꾼다. 받다 끊긴 파일이 완성본처럼 남지 않게
#   하는 원자적 교체다.
# - 실패가 하나라도 있으면 종료 코드 2로 끝내, 자동화에서 '일부만 받은 상태'를 성공으로 오인하지 않게
#   한다(Codex 1차 검토 지적 3).
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
            # 해시가 다른 기존 파일은 .bad로 이름을 바꿔 치운다. 그대로 두면 inventory나 rides가 깨진 파일을
            # 분석할 수 있다.
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


# _SPORT_LINE: GC 내보내기 JSON에서 '"sport":"..."' 한 줄을 찾는 정규식. 값 안에 이스케이프되지 않은
# 큰따옴표가 들어간 깨진 파일을 고칠 때 쓴다(load_meta).
_SPORT_LINE = re.compile(r'("sport":")(.*?)(",?\s*\n)')


# 선수 메타데이터 JSON({id}.json: ATHLETE와 RIDES)을 읽는다. 호출 위치: inventory_of_zip, rides.py,
# tests/test_opendata.py.
# 두 가지 GC 내보내기 결함을 견딘다. (1) 문자열 안의 제어 문자는 strict=False로 허용한다. (2) 사용자가 입력한
# sport 문자열의 따옴표가 이스케이프되지 않은 경우, 처음 파싱이 실패하면 그 줄 안의 큰따옴표만 작은따옴표로
# 바꿔 한 번 더 읽는다. 데이터를 버리지 않고 최소한만 고쳐 그 선수의 라이딩을 계속 쓰기 위함이다. 바이트는
# UTF-8로 풀되 깨진 바이트는 대체 문자로 바꾼다.
def load_meta(raw: bytes) -> dict:
    """Parse an athlete's metadata JSON, repairing GoldenCheetah's unescaped quotes.

    One export (athlete 8fc5800d, 2026-10-10) contains ``"sport":""3.5-4.5 hours""``: the user-typed
    sport string was written without escaping. If strict parsing fails, inner double quotes on
    ``sport`` lines are replaced by single quotes and parsing is retried once.
    """
    text = raw.decode("utf-8", "replace")
    # strict=False: another export (athlete d0375ff2) has a raw control character inside a string.
    try:
        return json.loads(text, strict=False)
    except json.JSONDecodeError:
        repaired = _SPORT_LINE.sub(lambda m: m.group(1) + m.group(2).replace('"', "'") + m.group(3), text)
        return json.loads(repaired, strict=False)


# 라이딩 METRICS에서 숫자 하나를 안전하게 꺼낸다. GC는 값을 문자열로, 때로는 목록(예: ["180.5", "3600"])으로
# 저장하므로 목록이면 첫 원소를 쓴다. 비었거나 'nan'·'inf'이거나 float로 바꿀 수 없거나 유한하지 않으면(예:
# '1e309' → inf) None. 'Infinity'가 양수 평균 파워로 인정되던 문제를 고친 결과다(Codex 1차 검토 지적 9). 호출
# 위치: inventory_of_zip, rides.py.
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


# CSV(첫 줄은 헤더 secs,km,power,...)의 첫 두 데이터 행 secs 차이로 기록 간격을 대략 추정한다(소수 둘째 자리).
# inventory의 참고용 표본 조사일 뿐이고, 파리티 측정의 기록 간격은 Kotlin 쪽 RecordingInterval이 전체 간격의
# 최빈값으로 따로 구한다.
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


# 선수 zip 하나를 풀지 않고 요약한다. 호출 위치: cmd_inventory, tests/test_opendata.py.
# - bike: 종목이 Bike, Turbo, 빈 문자열(미상)인 라이딩
# - power: 그중 메타데이터 average_power > 0인 라이딩(요약용 기준. 파리티·FTP 실험의 파워 여부는 rides.py가
#   data 플래그 'P'로 판정한다)
# - cp_changes: cp_setting을 날짜순으로 늘어놓았을 때 값이 바뀐 횟수. 처음엔 저장 순서로 세던 것을 날짜순으로
#   고쳤다(Codex 1차 검토 지적 9). FTP 라벨이 얼마나 자주 갱신되는지(3번 질문) 가늠하는 값이다
# - intervals_probe: 앞 csv_probe(50)개 CSV만 표본으로 본 간격 분포의 상위 4개
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


# inventory 하위 명령. --sample을 주면 매니페스트에 있는 선수만 요약한다(표본 밖 zip이 폴더에 섞여 있어도
# 집계에 들어가지 않게; Codex 1차 검토 지적 4). 깨진 zip이나 JSON은 error 행으로 남기고 계속 진행한다.
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


# split 하위 명령. inventory에서 파워 라이딩이 min_power_rides(기본 100, docs/plan.md의 '파워 라이딩 100개
# 이상') 이상인 선수만 남겨 선수 id → 분할 표를 쓴다. 분할 규칙 문자열을 결과 안에 함께 적어 파일만 봐도
# 어떻게 나눴는지 알 수 있게 한다.
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


# 명령행 진입점. 하위 명령 다섯 개(manifest, sample, download, inventory, split)의 인자를 정의하고 해당 cmd_
# 함수로 넘긴다.
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
