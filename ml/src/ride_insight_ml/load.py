"""Stage-5 load check (docs/experiments.md): concurrent FIT uploads and reads against a running server.

Usage: ``python -m ride_insight_ml.load --target http://127.0.0.1:8080 --fit <file.fit> --out <report.json>``

Scenario (pre-registered): 10 athletes are created; the same FIT file is uploaded to all 10 at once,
three rounds (every round a fresh set of athletes, because a repeat upload would be a 409); then 20
concurrent reads of ``samples?every=1`` and ``power-curve`` on the uploaded activities. Reports p50
and p95 latency per phase and the count of non-2xx responses. No verdict except "no 5xx".
"""

# [파일 역할] 5단계 부하 시험 스크립트. 실행 중인 서버에 같은 FIT 파일을 동시에 올리고(10개 × 3회), 올라간
# 활동의 시계열·파워 커브를 동시에 20번 읽어 단계별 응답 시간(p50·p95·최대)과 상태 코드 분포를 JSON으로
# 남긴다.
# [왜 이렇게 했나] 측정 규칙(docs/experiments.md 5단계 '부하')이 도구를 'Python concurrent.futures + urllib'로
# 미리 정했다. 외부 부하 도구 없이 표준 라이브러리와 numpy만으로 돌아가 재현이 쉽다. 판정 기준은 '5xx
# 0건'(H10) 하나이고 응답 시간은 보고만 한다(목표치를 미리 정할 근거가 없어서).
# [결과] reports/load/2026-10-10-elemnt.json(조건 위반 1차), 2026-10-10-elemnt-quiet.json(다른 Gradle 작업
# 없는 2차, 규칙 변경 8). 두 번 모두 5xx 0.

from __future__ import annotations

# 표준 라이브러리: 스레드 풀(concurrent.futures)로 동시 요청을 만들고, urllib로 HTTP를 보낸다. uuid는
# multipart 경계 문자열, time.perf_counter는 지연 시간 측정에 쓴다.
import argparse
import http.client
import json
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib import request
from urllib.error import HTTPError

# numpy: 지연 시간 배열의 백분위수(p50, p95) 계산.
import numpy as np

# 응답을 받지 못한 요청(연결 거부·끊김·타임아웃)의 상태 코드. HTTP 상태가 아니므로 0으로 둔다.
CONNECTION_ERROR = 0


# HTTP 요청 하나를 보내고 (상태 코드, 걸린 초, 응답 본문)을 돌려준다. urllib은 4xx·5xx를 HTTPError 예외로
# 던지므로 잡아서 일반 응답처럼 돌려줘, 실패 응답도 상태 코드와 지연 시간을 함께 집계한다. 연결
# 거부·끊김·타임아웃(URLError 등 OSError, 본문 도중 끊긴 IncompleteRead 등 HTTPException)은 상태
# CONNECTION_ERROR(0)와 예외 설명으로 돌려줘 2xx가 아닌 응답으로 센다. 처음 구현은 이것을 잡지 않아 요청
# 하나가 끊기면 스레드 풀이 예외를 다시 던져 스크립트 전체가 멈췄다(2026-10-13 수정).
# 시간은 요청을 열기 직전부터 본문을 다 읽을 때까지를 잰다(perf_counter는 단조 증가하는 고해상도 시계).
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
    except (OSError, http.client.HTTPException) as e:
        return CONNECTION_ERROR, time.perf_counter() - t0, repr(e).encode()


# 파일 하나짜리 multipart/form-data 본문을 직접 만든다. urllib에는 multipart 인코더가 없어서다. 필드 이름
# "file"은 서버 업로드 API(POST /athletes/{id}/activities, 파일 1개)가 받는 이름이다. 경계 문자열에 uuid를
# 넣어 FIT 바이너리 안의 바이트열과 겹칠 일이 사실상 없게 한다. 본문은 한 번 만들어 모든 업로드에 재사용한다.
def _multipart(name: str, payload: bytes) -> tuple[bytes, str]:
    boundary = f"----ri{uuid.uuid4().hex}"
    head = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    return head + payload + tail, f"multipart/form-data; boundary={boundary}"


# 선수 n명을 순서대로 만들고 id 목록을 돌려준다. FTP 250 W를 함께 등록해 업로드 때 IF·TSS까지 지표 계산 전체가
# 돌게 한다. 생성 실패는 assert로 바로 멈춘다. 이 단계는 준비 단계라 시간을 재지 않는다.
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


# 한 측정 단계: calls(인자 없는 함수 목록)를 workers개 스레드로 동시에 실행하고 지연 시간 p50·p95·최대(ms),
# 2xx가 아닌 응답 수(연결 오류 포함), 연결 오류 수, 상태 코드별 개수를 낸다. results(원시 결과)는 호출한 쪽이
# 활동 id를 꺼낸 뒤 보고서에서 뺀다.
# 스레드를 쓰는 이유: 요청은 대부분 서버 응답을 기다리는 I/O라 GIL이 있어도 스레드로 동시성을 충분히 만들 수
# 있다. workers를 요청 수와 같게 두어 모든 요청이 한꺼번에 나가게 한다.
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
        "connection_errors": int(sum(1 for s in statuses if s == CONNECTION_ERROR)),
        "status_counts": {str(s): statuses.count(s) for s in sorted(set(statuses))},
        "results": results,
    }


# 명령행 진입점. 기본값(--rounds 3, --uploads 10, --reads 20)이 미리 등록한 시나리오 그대로다.
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
    # 업로드 회차마다 새 선수 10명을 만든다. 같은 선수에게 같은 파일(같은 시작 시각)을 다시 올리면 서버가
    # 409(중복)를 돌려주므로 회차마다 선수를 바꿔야 매번 실제 저장 경로를 잰다. 1회차는 JVM JIT 워밍업 때문에
    # 느리게 나왔다(docs/experiments.md 5단계 결과).
    for r in range(a.rounds):
        ids = create_athletes(a.target, a.uploads)
        # lambda i=i: 기본 인자로 현재 i를 묶어 둔다. 그냥 i를 쓰면 반복이 끝난 뒤의 마지막 i를 모든 lambda가
        # 공유하는(늦은 바인딩) 파이썬 함정에 걸린다. 아래 read_calls의 path=path도 같은 이유다.
        calls = [
            (lambda i=i: _req("POST", f"{a.target}/api/athletes/{i}/activities", body, ctype)) for i in ids
        ]
        ph = phase(f"upload-round-{r + 1}", calls, a.uploads)
        # 201로 저장된 업로드의 활동 id만 모아 읽기 단계에 쓴다.
        for status, _, data in ph.pop("results"):
            if status == 201:
                activity_ids.append(json.loads(data)["id"])
        phases.append(ph)

    # 읽기 단계: 짝수 번째는 전체 해상도 시계열(samples?every=1, 응답이 가장 큰 조회), 홀수 번째는 파워 커브를
    # 부른다. 업로드된 활동을 돌아가며 쓴다. 업로드가 하나도 성공하지 못했으면 읽을 활동이 없으므로 읽기
    # 단계를 건너뛰고 그 사실을 보고서에 남긴다. 처음 구현은 여기서 0으로 나누기 오류로 끝나 업로드 결과도
    # 남기지 못했다(2026-10-13 수정).
    reads_skipped = None
    if activity_ids:
        read_calls = []
        for i in range(a.reads):
            act = activity_ids[i % len(activity_ids)]
            path = (
                f"/api/activities/{act}/samples?every=1"
                if i % 2 == 0
                else f"/api/activities/{act}/power-curve"
            )
            read_calls.append(lambda path=path: _req("GET", f"{a.target}{path}"))
        ph = phase("reads", read_calls, a.reads)
        ph.pop("results")
        phases.append(ph)
    else:
        reads_skipped = "no upload succeeded"

    # 보고서: 대상 주소, 파일 이름·크기, 단계별 결과, 5xx 존재 여부(H10 판정에 쓰는 값), 연결 오류 여부, 읽기
    # 단계를 건너뛴 이유(건너뛰었을 때만).
    report = {
        "target": a.target,
        "fit": a.fit.name,
        "fit_bytes": len(payload),
        "phases": phases,
        "any_5xx": any(int(s) >= 500 for ph in phases for s in ph["status_counts"]),
        "any_connection_error": any(ph["connection_errors"] > 0 for ph in phases),
    }
    if reads_skipped:
        report["reads_skipped"] = reads_skipped
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(report, indent=1), encoding="utf-8")
    for ph in phases:
        print(
            f"{ph['phase']}: n={ph['n']} p50={ph['p50_ms']:.0f}ms p95={ph['p95_ms']:.0f}ms "
            f"max={ph['max_ms']:.0f}ms statuses={ph['status_counts']}"
        )
    if reads_skipped:
        print("reads skipped:", reads_skipped)
    print("any_5xx", report["any_5xx"])


if __name__ == "__main__":
    main()
