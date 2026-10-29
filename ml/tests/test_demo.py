# [테스트 묶음] demo.py(데모 시드: 데모 선수를 만들고 샘플 FIT를 올린다)의 테스트. 실제 서버 대신 표준
# 라이브러리 http.server로 띄운 가짜 서버가 요청을 기록하고, 파일 이름마다 정해 둔 상태 코드와 본문으로
# 답한다. 그 위에서 도구가 선수를 FTP 250 W·Asia/Seoul로 만드는지, 샘플 9개를 모두 그 선수에게 multipart로
# 올리는지(파일 바이트 그대로), 상태 코드를 세어 출력하는지, 기대와 다른 답·선수 생성 실패·연결 실패·샘플 파일
# 없음을 종료 코드로 알리는지를 본다.
# [왜 중요한가] CI의 전체 스택 스모크 잡이 이 도구의 종료 코드로 nginx·서버 이미지 회귀를 판정한다. 기대와
# 다른 답을 통과시키면 회귀를 놓치고, 정상 답을 실패로 세면 CI가 늘 빨갛다.

import http.server
import json
import re
import socket
import threading
from pathlib import Path
from types import SimpleNamespace

import pytest

from ride_insight_ml import demo

# 서버 통합 테스트(ApiIntegrationTest의 완료 기준 2)와 같은 기대: 사이클링 5개 201, 러닝 422, 손상 2개 400,
# 개발자 필드 정렬이 어긋난 Coros는 201 또는 400. 도구의 표와 따로 적어 두 쪽이 어긋나면 테스트가 잡는다.
CYCLING = [
    "Edge810-Vector-2013-08-16-15-35-10.fit",
    "garmin-edge-500-activity.fit",
    "garmin-edge-820-bike.fit",
    "2019-02-17-062644-ELEMNT-297E-195-0.fit",
    "sample-activity-indoor-trainer.fit",
]
RUNNING = "garmin-fenix-5-run.fit"
CORRUPT = ["activity-unexpected-eof.fit", "activity-settings-corruptheader.fit"]
COROS = "coros-pace-2-cycling-misaligned-fields.fit"
EXPECTED = {**{n: {201} for n in CYCLING}, RUNNING: {422}, **{n: {400} for n in CORRUPT}, COROS: {201, 400}}
REPO_FIT_DIR = Path(__file__).resolve().parents[2] / "server" / "src" / "test" / "resources" / "fit"


# 정상 서버의 답: 사이클링은 저장(201, sport cycling), 러닝은 422 unsupported_sport, 손상 파일과 Coros는 400
# invalid_fit.
def _normal_answers() -> dict[str, tuple[int, dict]]:
    answers = {
        n: (201, {"id": f"act-{i}", "sport": "cycling", "startedAt": "2019-02-17T06:26:44Z"})
        for i, n in enumerate(CYCLING)
    }
    answers[RUNNING] = (422, {"error": "unsupported_sport", "message": "running is not supported"})
    for n in [*CORRUPT, COROS]:
        answers[n] = (400, {"error": "invalid_fit", "message": "bad header"})
    return answers


# 가짜 서버: POST /api/athletes는 athlete에 둔 답을, 업로드는 multipart 본문의 filename으로 answers에서 답을
# 찾는다 (없으면 500). 요청마다 (경로, Content-Type, 본문)을 requests에 쌓는다. 포트 0으로 열어 빈 포트를
# 쓴다.
@pytest.fixture
def service():
    requests: list[tuple[str, str, bytes]] = []
    answers: dict[str, tuple[int, dict]] = _normal_answers()
    athlete = {"status": 201, "body": {"id": "athlete-1", "name": "demo"}}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            requests.append((self.path, self.headers.get("Content-Type", ""), body))
            if self.path == "/api/athletes":
                status, payload = athlete["status"], athlete["body"]
            else:
                m = re.search(rb'filename="([^"]+)"', body)
                status, payload = answers.get(m.group(1).decode() if m else "", (500, {"error": "internal"}))
            data = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        # 테스트 출력에 접속 기록을 남기지 않는다.
        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    # poll_interval: shutdown()이 기다리는 간격(기본 0.5초)을 줄여 테스트마다 끝내는 시간을 줄인다.
    threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
    try:
        url = f"http://127.0.0.1:{server.server_address[1]}"
        yield SimpleNamespace(url=url, requests=requests, answers=answers, athlete=athlete)
    finally:
        server.shutdown()
        server.server_close()


# 샘플 이름 9개로 내용이 서로 다른 가짜 파일을 만든다(올린 본문에 그 바이트가 그대로 들어갔는지 보려고).
@pytest.fixture
def fit_dir(tmp_path: Path) -> Path:
    d = tmp_path / "fit"
    d.mkdir()
    for name in EXPECTED:
        (d / name).write_bytes(b"\x0e\x10FIT " + name.encode())
    return d


def _run(service, fit_dir) -> int:
    return demo.main(["--target", service.url, "--fit-dir", str(fit_dir)])


# 정상 서버: 0을 돌려주고, 선수를 FTP 250·Asia/Seoul로 한 번 만든 뒤 9개 파일을 그 선수의 업로드 경로에
# multipart로 (파일 바이트 그대로) 올리며, 선수 id와 상태 코드별 개수를 출력한다.
def test_demo_creates_the_athlete_uploads_every_sample_and_counts_codes(service, fit_dir, capsys):
    assert _run(service, fit_dir) == 0

    path, ctype, body = service.requests[0]
    assert path == "/api/athletes" and ctype.startswith("application/json")
    created = json.loads(body)
    assert created["ftp"] == 250 and created["timeZone"] == "Asia/Seoul" and created["name"]
    uploads = service.requests[1:]
    assert len(uploads) == 9
    names = []
    for path, ctype, body in uploads:
        assert path == "/api/athletes/athlete-1/activities"
        assert ctype.startswith("multipart/form-data; boundary=")
        name = re.search(rb'name="file"; filename="([^"]+)"', body).group(1).decode()
        assert (fit_dir / name).read_bytes() in body
        names.append(name)
    assert sorted(names) == sorted(EXPECTED)
    out = capsys.readouterr().out
    assert "athlete athlete-1" in out
    assert 'status counts: {"201": 5, "400": 3, "422": 1}' in out
    assert "all 9 files answered as expected" in out


# 사이클링 파일 하나가 저장되지 않으면(500) 1이고, 그 파일 이름과 받은 상태 코드를 출력한다. 나머지 파일은
# 계속 올린다.
def test_demo_fails_when_a_cycling_file_is_not_stored(service, fit_dir, capsys):
    service.answers["garmin-edge-500-activity.fit"] = (500, {"error": "internal"})

    assert _run(service, fit_dir) == 1

    assert len(service.requests) == 10
    out = capsys.readouterr().out
    assert "UNEXPECTED garmin-edge-500-activity.fit: 500" in out
    assert "1 of 9 files answered differently" in out


# Coros 파일은 저장(201)되든 잘못된 FIT(400 invalid_fit)로 거절되든 정상이고, 500이면 실패다.
@pytest.mark.parametrize(
    ("status", "body", "code"),
    [
        (201, {"id": "c", "sport": "cycling"}, 0),
        (400, {"error": "invalid_fit"}, 0),
        (500, {"error": "internal"}, 1),
    ],
)
def test_coros_may_be_stored_or_rejected_but_not_fail(service, fit_dir, status, body, code):
    service.answers[COROS] = (status, body)
    assert _run(service, fit_dir) == code


# 상태 코드가 맞아도 본문이 서버의 답이 아니면 실패다: nginx 같은 앞단이 낸 400(서버의 invalid_fit이 아님),
# 종목이 cycling이 아닌 201. 손상 파일이 앞단에서 거절돼도 통과시키면 서버 경로가 깨진 것을 놓친다.
@pytest.mark.parametrize(
    ("name", "answer"),
    [
        (CORRUPT[0], (400, {"error": "bad_request"})),
        (CYCLING[0], (201, {"id": "x", "sport": None})),
        (RUNNING, (422, {"error": "validation"})),
    ],
)
def test_right_status_with_the_wrong_body_is_a_failure(service, fit_dir, capsys, name, answer):
    service.answers[name] = answer
    assert _run(service, fit_dir) == 1
    assert f"UNEXPECTED {name}" in capsys.readouterr().out


# 선수를 만들지 못하면(503) 올리기 전에 1로 끝낸다.
def test_athlete_creation_failure_stops_before_uploading(service, fit_dir, capsys):
    service.athlete.update(status=503, body={"error": "unavailable"})

    assert _run(service, fit_dir) == 1

    assert len(service.requests) == 1
    assert "could not create the demo athlete: 503" in capsys.readouterr().out


# 서버에 닿지 않으면(닫힌 포트) 예외로 멈추지 않고 1과 안내를 낸다.
def test_unreachable_target_is_reported(fit_dir, capsys):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    assert demo.main(["--target", f"http://127.0.0.1:{port}", "--fit-dir", str(fit_dir)]) == 1
    assert "could not reach" in capsys.readouterr().out


# 샘플 파일이 하나라도 없으면 요청을 보내기 전에 2로 끝내고 없는 이름을 출력한다.
def test_missing_sample_files_are_reported_before_any_request(service, fit_dir, capsys):
    (fit_dir / RUNNING).unlink()

    assert _run(service, fit_dir) == 2

    assert service.requests == []
    assert RUNNING in capsys.readouterr().out


# 도구의 표가 위 기대와 같고, 기본 폴더(저장소의 server/src/test/resources/fit)의 샘플 FIT 전체를 덮는다.
def test_the_demo_covers_every_committed_sample_fit():
    assert {s.name: set(s.statuses) for s in demo.SAMPLES} == EXPECTED
    assert demo.FIT_DIR.resolve() == REPO_FIT_DIR
    assert sorted(p.name for p in REPO_FIT_DIR.glob("*.fit")) == sorted(EXPECTED)
