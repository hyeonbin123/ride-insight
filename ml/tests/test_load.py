# [테스트 묶음] load.py(5단계 부하 시험 스크립트)의 실패 경로 테스트. 실제 서버 없이 연결이 거부되는 주소와
# 가짜 _req로, 연결 오류나 업로드 전부 실패가 스크립트를 멈추지 않고 보고서에 남는지 확인한다.
# [왜 중요한가] 부하 시험은 서버가 버티지 못하는 상황을 기록하려고 돌린다. 그때 스크립트가 먼저 죽으면 무엇이
# 실패했는지 남지 않는다.

import json
import socket
from pathlib import Path

from ride_insight_ml import load


# 닫힌 포트(방금 열었다 닫은 포트)로 요청하면 예외 대신 상태 CONNECTION_ERROR(0)로 돌아와야 한다. 처음
# 구현은 URLError를 잡지 않아 여기서 예외가 났다.
def test_connection_errors_are_returned_not_raised():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    status, seconds, body = load._req("GET", f"http://127.0.0.1:{port}/")
    assert status == load.CONNECTION_ERROR
    assert seconds >= 0
    assert body


# 업로드가 하나도 성공하지 못하면(여기서는 모든 요청이 503) 읽기 단계를 건너뛰고 보고서를 써야 한다. 처음
# 구현은 활동 id 목록이 비어 0으로 나누기 오류로 끝났다. 연결 오류도 섞어 연결 오류 수가 집계되는지 본다.
def test_report_is_written_when_no_upload_succeeds(tmp_path: Path, monkeypatch):
    calls = {"n": 0}

    def fake_req(method, url, body=None, content_type=None):
        calls["n"] += 1
        return (load.CONNECTION_ERROR, 0.01, b"refused") if calls["n"] % 2 else (503, 0.02, b"{}")

    monkeypatch.setattr(load, "create_athletes", lambda target, n: [f"x{i}" for i in range(n)])
    monkeypatch.setattr(load, "_req", fake_req)
    fit = tmp_path / "ride.fit"
    fit.write_bytes(b"\x0e\x10")
    out = tmp_path / "report.json"
    load.main(["--fit", str(fit), "--out", str(out), "--rounds", "1", "--uploads", "4", "--reads", "6"])
    report = json.loads(out.read_text(encoding="utf-8"))
    assert [ph["phase"] for ph in report["phases"]] == ["upload-round-1"]
    assert report["phases"][0]["non_2xx"] == 4
    assert report["phases"][0]["connection_errors"] == 2
    assert report["reads_skipped"] == "no upload succeeded"
    assert report["any_5xx"] is True
    assert report["any_connection_error"] is True


# 보고서 파일의 줄 끝은 OS와 무관하게 LF여야 한다(2026-11-05 추가). 다른 보고서 writer(parity·ftp·parity_sets·
# opendata)는 newline="\n"을 주는데 이 파일만 빠져 있어, Windows에서는 indent가 만든 줄바꿈이 CRLF로 쓰였다.
# 저장소의 .gitattributes가 커밋 때 LF로 맞추므로 커밋된 reports/load/*.json에는 CRLF가 없지만, 작업 폴더의 새
# 보고서는 바이트가 달랐다. 이 테스트는 Linux(CI)에서는 고치기 전에도 통과하고, Windows에서만 고치기 전
# 코드를 잡는다. 업로드 2개가 모두 201이고 읽기 2개가 200인 가짜 _req로 읽기 단계까지 돌려, 보고서 전체를
# 쓰는 경로를 본다.
def test_report_is_written_with_lf_line_endings(tmp_path: Path, monkeypatch):
    def fake_req(method, url, body=None, content_type=None):
        if method == "POST":
            return 201, 0.01, json.dumps({"id": "act-1"}).encode()
        return 200, 0.01, b"{}"

    monkeypatch.setattr(load, "create_athletes", lambda target, n: [f"x{i}" for i in range(n)])
    monkeypatch.setattr(load, "_req", fake_req)
    fit = tmp_path / "ride.fit"
    fit.write_bytes(b"\x0e\x10")
    out = tmp_path / "report.json"
    load.main(["--fit", str(fit), "--out", str(out), "--rounds", "1", "--uploads", "2", "--reads", "2"])
    data = out.read_bytes()
    assert b"\r\n" not in data
    assert data.count(b"\n") > 10  # indent=1이라 여러 줄이어야 줄 끝을 본 것이 된다
    report = json.loads(data.decode("utf-8"))
    assert [ph["phase"] for ph in report["phases"]] == ["upload-round-1", "reads"]
    assert report["any_5xx"] is False
