"""ZAP 프록시를 거쳐 전체 스택(nginx 웹 + 프록시된 API)에 씨앗 요청을 보낸다.

FIT 업로드(multipart)를 포함한다.

사용법: python zap/proxy_seed.py --proxy 127.0.0.1:8099 --target http://127.0.0.1:8081 \\
    --fit server/src/test/resources/fit/garmin-edge-820-bike.fit --done <ZAP이 기다리는 파일>

zap/automation-stack.yaml의 첫 작업(delay)이 --done 파일이 생길 때까지 기다리는 동안 이 스크립트를 실행한다.
"""

# [파일 역할] 2026-10-15 DAST 범위 넓히기(docs/experiments.md 5단계 'DAST 범위 넓히기'). 처음 ZAP
# 계획(zap/automation.yaml)은 HAR 씨앗으로 JSON 요청만 넣어 FIT 업로드가 빠졌다(HAR의 텍스트 본문에는
# 바이너리 파일을 담을 수 없다). 그래서 ZAP을 프록시로 띄워 두고 이 스크립트가 실제 업로드를 포함한 요청을
# 프록시로 보내, ZAP의 사이트 트리에 업로드 요청이 들어가게 한다. active scan이 그 요청의 multipart
# 파트(파일 이름·내용)까지 변형해 공격한다.
# [왜 http.client인가] urllib의 ProxyHandler는 Windows 레지스트리의 프록시 예외 설정에 따라 로컬 주소를
# 프록시 없이 보낼 수 있다. http.client로 프록시에 직접 연결해 절대 URL(absolute-form)로 요청하면 그런
# 예외가 없다.
# [안전] 대상은 로컬 스택만 쓴다(원격 주소 금지). DELETE는 보내지 않는다(데모 데이터 보호,
# automation.yaml과 같은 방침).

from __future__ import annotations

import argparse
import http.client
import json
import uuid
from pathlib import Path


# 프록시(host:port)에 연결해 절대 URL로 요청 하나를 보내고 (상태 코드, 본문)을 돌려준다.
def call(
    proxy: str, method: str, url: str, body: bytes | None = None, ctype: str | None = None
) -> tuple[int, bytes]:
    host, port = proxy.split(":")
    conn = http.client.HTTPConnection(host, int(port), timeout=120)
    headers = {"Accept": "application/json, text/html"}
    if ctype:
        headers["Content-Type"] = ctype
    conn.request(method, url, body=body, headers=headers)
    res = conn.getresponse()
    data = res.read()
    conn.close()
    print(f"{method} {url} -> {res.status}")
    return res.status, data


# 파일 하나짜리 multipart/form-data 본문(서버 업로드 API가 받는 'file' 파트).
def multipart(name: str, payload: bytes) -> tuple[bytes, str]:
    boundary = f"----zap{uuid.uuid4().hex}"
    head = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    return head + payload + f"\r\n--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--proxy", default="127.0.0.1:8099", help="ZAP 프록시 주소(host:port)")
    p.add_argument("--target", default="http://127.0.0.1:8081", help="스택의 웹 주소(nginx), 로컬만")
    p.add_argument("--fit", type=Path, required=True, help="올릴 FIT 파일(작은 샘플 권장)")
    p.add_argument("--done", type=Path, required=True, help="요청을 다 보낸 뒤 만들 파일(ZAP이 기다림)")
    a = p.parse_args(argv)
    t = a.target.rstrip("/")
    js = "application/json"
    try:
        # 화면: SPA 입구와 정적 파일.
        call(a.proxy, "GET", f"{t}/")
        call(a.proxy, "GET", f"{t}/favicon.svg")
        # 선수 생성(FTP 포함) → FTP 변경 → 시간대 변경.
        status, data = call(
            a.proxy,
            "POST",
            f"{t}/api/athletes",
            json.dumps(
                {"name": "zap seed", "gender": "M", "birthYear": 1990, "weightKg": 70, "ftp": 250}
            ).encode(),
            js,
        )
        assert status == 201, data
        athlete = json.loads(data)["id"]
        call(
            a.proxy,
            "PUT",
            f"{t}/api/athletes/{athlete}/threshold",
            json.dumps({"effectiveFrom": "2019-01-01", "ftpWatts": 260}).encode(),
            js,
        )
        call(
            a.proxy,
            "PUT",
            f"{t}/api/athletes/{athlete}/time-zone",
            json.dumps({"timeZone": "Asia/Seoul"}).encode(),
            js,
        )
        # FIT 업로드(multipart) → 활동 id.
        body, ctype = multipart(a.fit.name, a.fit.read_bytes())
        status, data = call(a.proxy, "POST", f"{t}/api/athletes/{athlete}/activities", body, ctype)
        assert status == 201, data
        activity = json.loads(data)["id"]
        # 선수·활동 읽기 전부.
        for path in [
            f"/api/athletes/{athlete}",
            f"/api/athletes/{athlete}/thresholds",
            f"/api/athletes/{athlete}/activities?limit=10",
            f"/api/athletes/{athlete}/training-load?from=2019-01-01&to=2019-03-01",
            f"/api/athletes/{athlete}/power-curve?weeks=52&asOf=2019-03-01",
            f"/api/athletes/{athlete}/ftp-estimate?weeks=52&asOf=2019-03-01",
            f"/api/activities/{activity}",
            f"/api/activities/{activity}/samples?every=10",
            f"/api/activities/{activity}/power-curve",
        ]:
            call(a.proxy, "GET", f"{t}{path}")
    finally:
        # 실패해도 ZAP이 끝까지 기다리지 않도록 완료 파일은 항상 만든다.
        a.done.write_text("done\n", encoding="utf-8")


if __name__ == "__main__":
    main()
