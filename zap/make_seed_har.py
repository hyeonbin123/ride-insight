"""OWASP ZAP 스캔(zap/automation.yaml)에 쓸 HAR 씨앗 파일을 만든다.

씨앗에는 ZAP이 JSON API를 spider로 훑어서는 찾을 수 없는 JSON 요청이 들어 있다: 선수 생성, FTP 변경,
그리고 선수 하나·활동 하나에 대한 모든 읽기 끝점. multipart FIT 업로드는 씨앗에 넣지 않는다(HAR의 텍스트
본문에는 바이너리 파일을 담을 수 없다). 업로드의 견고성은 대신 통합 테스트와 변조 파일 테스트가
다룬다(docs/experiments.md, 5단계).

사용법: python zap/make_seed_har.py --target http://127.0.0.1:8080 --athlete <id> --activity <id> --out work/zap/seed.har
"""

# [파일 역할] 5단계 보안 점검(OWASP ZAP DAST)의 시작점이 될 요청 목록을 HAR 파일로 만든다.
# zap/automation.yaml의 import 작업이 이 HAR를 읽어 요청을 사이트 트리에 넣고, 그 뒤 spider와 active
# scan이 이 요청들을 출발점으로 공격 요청을 만든다.
# [왜 필요한가] JSON API에는 링크가 담긴 HTML이 없어 spider만으로는 끝점을 거의 찾지 못한다. 그래서
# 선수·활동 읽기 끝점 전부와 쓰기 요청(선수 생성, FTP 변경)을 직접 씨앗으로 준다. 1차 스캔(이 씨앗
# 11개 + spider + active scan)은 존재하지 않는 경로에 500을 돌려주던 버그(spider가 /, /api,
# /robots.txt를 두드려 드러남)와 보안 헤더 누락을 잡았다(docs/experiments.md 5단계 결과,
# reports/dast/).
# [넣지 않은 것] FIT 업로드(multipart 바이너리는 HAR 텍스트 본문에 담을 수 없어 통합 테스트·변조 FIT
# 테스트가 대신 다룬다)와 DELETE(스캔이 데모 데이터를 지우지 않도록, automation.yaml 주석).

from __future__ import annotations

# 표준 라이브러리만 쓴다(HAR는 JSON이다).
import argparse
import json
from pathlib import Path


# HAR 1.2 형식의 요청·응답 한 쌍(entry)을 만든다. 스캔의 출발점이 되는 것은 request 쪽(메서드, URL,
# 헤더, 본문)이고, response·timings·startedDateTime은 HAR 형식이 요구해서 넣는 고정 자리 값이다(상태
# 200, 본문 없음). 크기를 모르는 항목은 HAR 규칙대로 -1로 둔다. 본문이 있으면 Content-Type 헤더와
# postData를 함께 넣어 서버가 JSON으로 해석하게 한다.
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


# 명령행 진입점. --target(로컬 서버 주소), --athlete·--activity(이미 DB에 있는 데모 선수·활동 id),
# --out(HAR 경로)을 받는다. 실제 id를 넣어야 스캐너가 404가 아닌 정상 응답 경로를 공격 대상으로
# 삼는다.
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
    # 씨앗 요청 11개: health, 선수 조회·FTP 이력·활동 목록·훈련 부하·기간 파워 커브, 활동
    # 조회·시계열·파워 커브, 선수 생성(POST), FTP 변경(PUT). 조회 날짜 2019-02-17은 샘플 FIT 중
    # Wahoo ELEMNT 파일 이름의 날짜이고, FTP 315 W는 Edge810 샘플의 기기 FTP와 같은
    # 값이다(docs/api.md 완료 기준 3).
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
    # HAR 최상위 구조(log.version, creator, entries)로 감싸 파일로 쓴다.
    har = {"log": {"version": "1.2", "creator": {"name": "ride-insight make_seed_har", "version": "1"}, "entries": entries}}
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(har, indent=1), encoding="utf-8")
    print(f"wrote {a.out} with {len(entries)} entries")


if __name__ == "__main__":
    main()
