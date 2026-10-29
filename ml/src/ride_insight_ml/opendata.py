"""GoldenCheetah OpenData(OSF osf.io/6hfpz, CC0 1.0)의 표본 추출, 다운로드, 요약을 맡는다.

하위 명령(``python -m ride_insight_ml.opendata <cmd>``로 실행):

* ``manifest``  OSF 프로젝트의 모든 파일을 JSON 매니페스트로 정리한다(OSF API v2)
* ``sample``    매니페스트에서 선수 표본을 결정적으로 고른다(id의 SHA-256 순서)
* ``download``  표본 zip을 SHA-256으로 검증하며 받는다(이미 검증된 파일은 건너뜀, 연결이 120초 멎으면
  끊고 최대 5번까지 다시 시도)
* ``inventory`` 받은 선수마다 요약한다(라이딩, 파워 라이딩, 기록 간격, cp_setting 변경 횟수)
* ``split``     id의 SHA-256으로 선수마다 train/dev/test를 정한다

여기서는 라이딩 데이터를 다시 샘플링하거나 정리하지 않는다. 라이딩과 CSV 파일의 짝짓기는
``rides``가, 샘플 읽기와 정리는 Kotlin ``parity`` 도구(``OpenDataCsv``)가 한다.
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
# [줄 끝] 이 파일이 쓰는 JSON(manifest·sample·inventory·split)은 newline="\n"으로 LF로 쓴다. 지정하지 않으면
# Windows에서 CRLF가 되어, 같은 표본을 다른 OS에서 다시 만들면 내용이 같아도 파일 전체가 바뀐 것으로 보인다.
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
    """``domain:id``의 SHA-256을 돌려준다.

    표본 순서와 분할 배정은 서로 다른 domain을 쓴다. 해시 하나를 같이 쓰면 해시 순서로 앞선
    ``n``개 id가 모두 가장 낮은 분할 구간에 들어간다(2026-10-10 발견: 표본 800명이 전부 'train'이었다).
    """
    return hashlib.sha256(f"{domain}:{athlete_id}".encode()).hexdigest()


# zip 파일 이름에서 선수 id를 얻는다(.zip 접미사 제거). rides.py도 같은 규칙을 쓴다.
def athlete_id_from_name(name: str) -> str:
    """zip 파일 이름에서 선수 id를 얻는다. 예: '033874ce-....zip' -> '033874ce-...'."""
    return name[: -len(ATHLETE_ZIP_SUFFIX)] if name.endswith(ATHLETE_ZIP_SUFFIX) else name


# 선수 id → train/dev/test. SHA-256('split:' + id)의 앞 8자리 16진수(4바이트)를 2^32로 나눠 [0, 1) 사이 수로
# 만들고, 0.6 미만 train, 0.8 미만 dev, 나머지 test로 나눈다(60/20/20, docs/experiments.md 공통 규칙).
# 선수 단위로 정하므로 같은 선수의 라이딩이 여러 분할에 섞이지 않는다. 결과가 id만으로 정해져 표본에 선수를
# 더하거나 빼도 기존 선수의 분할은 변하지 않는다. 호출 위치: cmd_sample, cmd_split, tests/test_opendata.py.
def split_of(athlete_id: str, train: float = 0.6, dev: float = 0.2) -> str:
    """SHA-256('split:' + id)의 앞 4바이트를 [0, 1)로 옮긴 값으로 분할을 결정적으로 정한다."""
    u = int(_sha256_of_id(athlete_id, "split")[:8], 16) / 2**32
    if u < train:
        return "train"
    if u < train + dev:
        return "dev"
    return "test"


# ----------------------------------------------------------------------------- manifest: OSF 파일 목록


# URL에서 JSON을 받는다. 네트워크 오류면 5초, 10초, 15초 … 로 기다리는 시간을 늘려 가며 최대 5번 시도하고,
# 끝내 실패하면 마지막 예외를 원인으로 붙여 RuntimeError를 낸다. OSF API가 가끔 실패해도 수천 개 목록 수집이
# 중간에 끊기지 않게 하려는 것이다.
def _get_json(url: str, retries: int = 5) -> dict:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001 - 어떤 네트워크 오류든 다시 시도한다
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
    out.write_text(json.dumps(rows, indent=0), encoding="utf-8", newline="\n")
    files = [r for r in rows if r["kind"] == "file"]
    print(f"files={len(files)} total_gb={sum(r['size'] for r in files) / 1e9:.1f}")


# ----------------------------------------------------------------------------- sample: 표본 고르기


# 선수 zip만 골라 max_bytes(계획: 80 MB) 이하로 거른 뒤 SHA-256('sample:' + id) 순서로 정렬해 앞 n개를 고른다.
# 입력 순서와 무관하게 같은 결과가 나오는지, 크기 상한을 지키는지를 tests/test_opendata.py가 확인한다. 이
# 상한으로 전체 115.6 GB 중 받을 양이 표본 800명 12.4 GB가 됐다(docs/plan.md).
def choose_sample(files: list[dict], n: int, max_bytes: int | None) -> list[dict]:
    """SHA-256(id) 순서로 선수 zip 앞 ``n``개를 고른다. ``max_bytes``보다 큰 파일은 건너뛴다.

    이 순서는 고정된 의사 난수 순열이라 표본이 재현되고, 올린 순서나 파일 크기에 좌우되지
    않는다(명시한 크기 상한은 예외).
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
    out.write_text(json.dumps(rows, indent=1), encoding="utf-8", newline="\n")
    by_split = collections.Counter(r["split"] for r in rows)
    print(f"picked={len(rows)} bytes={sum(r['size'] for r in rows) / 1e9:.2f}GB splits={dict(by_split)}")


# ----------------------------------------------------------------------------- download: 내려받기와 검증


# 파일의 SHA-256. 1 MB(1 << 20)씩 읽어 수십 MB zip도 메모리에 통째로 올리지 않는다.
def _file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# DOWNLOAD_TIMEOUT_S: urlopen에 넘기는 소켓 타임아웃(초). 전체 내려받기 시간의 상한이 아니라, 연결하거나 한
# 번 읽을 때 이 시간 동안 바이트가 하나도 오지 않으면 socket.timeout(TimeoutError)을 내게 하는 값이다.
# 예전에는 urlretrieve를 timeout 없이 써서, OSF가 응답을 멈추면 예외 없이 영원히 기다렸다(재시도 경로에도
# 들어가지 않았다). 선수 zip은 최대 80 MB라 정상 전송 중 120초 동안 한 바이트도 안 오는 일은 없다고 보고,
# _get_json(60초)보다 넉넉하게 잡았다.
# DOWNLOAD_ATTEMPTS: 한 파일을 시도하는 최대 횟수. _get_json과 같이 5번, 사이에 5·10·15·20초를 기다린다.
# _DOWNLOAD_CHUNK: 응답을 .part 파일로 옮기는 단위(1 MiB). 메모리에 파일 전체를 올리지 않는다.
DOWNLOAD_TIMEOUT_S = 120
DOWNLOAD_ATTEMPTS = 5
_DOWNLOAD_CHUNK = 1 << 20


# url을 path에 1 MiB씩 쓰면서 SHA-256을 함께 계산해 16진 문자열로 돌려준다(한 번만 시도한다). 다 받은 뒤
# 파일을 다시 읽어 해시를 구하던 방식보다 디스크 읽기가 한 번 적다(표본 12.4 GB를 한 번 덜 읽는다).
# path는 'wb'로 열어 이전 시도의 조각이 있어도 처음부터 다시 쓴다. 연결 실패·HTTP 오류·타임아웃(멎은 연결)은
# 예외로 그대로 올린다. 이 함수는 받은 파일을 지우지 않으므로 실패 시 정리는 호출하는 쪽이 한다.
# 호출 위치: _fetch_with_retries(OpenData zip), fit_samples.main(샘플 FIT, 재시도 없이 timeout 60초).
def fetch_with_sha256(url: str, path: Path, timeout: float) -> str:
    """``url``의 응답을 ``path``에 쓰고 그 내용의 SHA-256 16진 문자열을 돌려준다."""
    h = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=timeout) as r, path.open("wb") as f:
        for chunk in iter(lambda: r.read(_DOWNLOAD_CHUNK), b""):
            f.write(chunk)
            h.update(chunk)
    return h.hexdigest()


# fetch_with_sha256을 최대 attempts번 시도한다. 실패할 때마다 받다 만 path를 지우고 stderr에 원인을 적은 뒤
# 5·10·15·20초(_get_json과 같은 간격: 시도 번호 × 5초)를 기다린다. 마지막 실패 뒤에는 기다리지 않고, 마지막
# 예외를 원인으로 붙인 RuntimeError를 낸다. 해시 비교는 하지 않는다: 끝까지 받았는데 해시가 다르면 다시 받아도
# 같은 내용일 가능성이 커서, 재시도 대상은 네트워크·전송 실패뿐이다(비교는 cmd_download가 한다).
def _fetch_with_retries(
    url: str, path: Path, attempts: int = DOWNLOAD_ATTEMPTS, timeout: float = DOWNLOAD_TIMEOUT_S
) -> str:
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            return fetch_with_sha256(url, path, timeout)
        except Exception as e:  # noqa: BLE001 - 어떤 네트워크 오류든 다시 시도한다(_get_json과 같다)
            last = e
            path.unlink(missing_ok=True)
            print(f"attempt {attempt + 1}/{attempts} failed: {e!r}", file=sys.stderr)
            if attempt + 1 < attempts:
                time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"{attempts} attempts failed, last error: {last!r}") from last


# download 하위 명령. 표본 매니페스트의 zip을 차례로 받는다(limit이 있으면 앞 limit명만).
# - 이미 있고 SHA-256이 맞으면 건너뛴다. 중간에 끊겨도 같은 명령을 다시 돌리면 남은 것만 받는다.
# - .part 임시 파일로 받은 뒤 해시가 맞을 때만 최종 이름으로 바꾼다. 받다 끊긴 파일이 완성본처럼 남지 않게
#   하는 원자적 교체다.
# - 연결이 멎으면 DOWNLOAD_TIMEOUT_S 뒤 끊고 다시 시도한다(_fetch_with_retries). 다섯 번 모두 실패한 파일은
#   failed로 세고 다음 선수로 넘어간다. 해시는 받으면서 계산한다(fetch_with_sha256).
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
            # 해시가 다른 기존 파일은 검증에 실패한 것이라 절대 분석에 쓰이면 안 된다. 먼저 .bad로 이름을
            # 바꿔 치운 뒤(2026-10-10 Codex 검토) 새 사본을 다시 받는다. 그대로 두면 inventory나 rides가 깨진
            # 파일을 분석할 수 있다.
            stale = target.with_suffix(".bad")
            target.replace(stale)
            print(f"stale file moved to {stale.name}", file=sys.stderr)
        tmp = target.with_suffix(".part")
        try:
            if _fetch_with_retries(r["download"], tmp) != r["sha256"]:
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


# ----------------------------------------------------------------------------- inventory: 선수별 요약


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
    """선수 메타데이터 JSON을 읽는다. GoldenCheetah가 이스케이프하지 않은 따옴표를 고쳐 읽는다.

    한 내보내기 파일(선수 8fc5800d, 2026-10-10)에 ``"sport":""3.5-4.5 hours""``가 들어 있다. 사용자가
    입력한 sport 문자열이 이스케이프 없이 기록된 것이다. 엄격한 파싱이 실패하면 ``sport`` 줄 안쪽의
    큰따옴표를 작은따옴표로 바꾸고 한 번 더 파싱한다.
    """
    text = raw.decode("utf-8", "replace")
    # strict=False: 다른 내보내기 파일(선수 d0375ff2)에는 문자열 안에 날것의 제어 문자가 들어 있다.
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
    """선수 zip 하나를 풀지 않고 요약한다."""
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
    """``dest``의 zip을 요약한다. ``sample``을 주면 그 매니페스트에 있는 선수만 요약한다."""
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
    out.write_text(json.dumps(rows, indent=1), encoding="utf-8", newline="\n")
    ok = [r for r in rows if "error" not in r]
    print(
        f"athletes={len(rows)} ok={len(ok)} power_rides_total={sum(r['power_rides'] for r in ok)} "
        f"with_cp_changes={sum(1 for r in ok if r['cp_changes'] > 0)}"
    )


# ----------------------------------------------------------------------------- split: 분할표


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
    out.write_text(json.dumps(result, indent=1), encoding="utf-8", newline="\n")
    print(f"kept={len(kept)} {dict(collections.Counter(result['athletes'].values()))}")


# ----------------------------------------------------------------------------- 명령행


# 명령행 진입점. 하위 명령 다섯 개(manifest, sample, download, inventory, split)의 인자를 정의하고 해당 cmd_
# 함수로 넘긴다.
def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("manifest", help="OSF 파일 목록을 JSON 매니페스트로 쓴다")
    s.add_argument("--out", type=Path, required=True)

    s = sub.add_parser("sample", help="선수 표본을 결정적으로 고른다(id의 SHA-256 순서)")
    s.add_argument("--manifest", type=Path, required=True)
    s.add_argument("--n", type=int, required=True)
    s.add_argument("--max-bytes", type=int, default=None)
    s.add_argument("--out", type=Path, required=True)

    s = sub.add_parser(
        "download",
        help=(
            "표본 zip을 SHA-256으로 검증하며 내려받는다. 이미 검증된 파일은 건너뛰고, 연결이 120초 동안 "
            "멎으면 끊고 다시 시도한다(최대 5번). 실패가 하나라도 있으면 종료 코드 2"
        ),
    )
    s.add_argument("--sample", type=Path, required=True)
    s.add_argument("--dest", type=Path, required=True)
    s.add_argument("--limit", type=int, default=None)

    s = sub.add_parser("inventory", help="내려받은 선수를 요약한다")
    s.add_argument("--dest", type=Path, required=True)
    s.add_argument("--out", type=Path, required=True)
    s.add_argument("--sample", type=Path, default=None, help="이 표본 매니페스트에 있는 선수만 요약한다")

    s = sub.add_parser("split", help="선수마다 train/dev/test를 정한다")
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
