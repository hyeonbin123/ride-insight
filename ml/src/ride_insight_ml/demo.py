"""데모 시드: 실행 중인 서비스에 데모 선수를 만들고 저장소의 샘플 FIT 9개를 올려 답을 확인한다.

사용법(전체 스택을 띄운 뒤 ml 폴더에서)::

    docker compose up --build -d --wait          # 저장소 루트에서
    uv run python -m ride_insight_ml.demo --target http://127.0.0.1:8081

데모 선수(FTP 250 W, 시간대 Asia/Seoul)를 하나 만들고 server/src/test/resources/fit/의 샘플
FIT(python-fitparse 테스트 파일, MIT)를 차례로 올린다. 정상 답은 서버 통합 테스트의 완료 기준 2와 같다:
사이클링 5개는 201(저장, 종목 cycling), 러닝 1개는 422 unsupported_sport, 손상 파일 2개는 400
invalid_fit, 개발자 필드 정렬이 어긋난 Coros 1개는 201 또는 400 invalid_fit. 선수 id, 파일마다의 답,
상태 코드별 개수를 출력한다.

종료 코드: 0 = 모든 파일이 기대대로 답했다, 1 = 기대와 다른 답이 있다(또는 선수를 만들지 못했거나 서버에 닿지
않았다), 2 = 샘플 파일이 없다 또는 옵션 오류.
"""

# [파일 역할] 저장소를 받은 사람이 빈 DB 대신 데모 데이터로 화면을 볼 수 있게 하는 시드 도구이자, CI의 전체
# 스택 스모크 잡이 쓰는 검사 도구. 웹(nginx, 8081)을 거쳐 올리면 정적 서버 뒤의 /api 프록시, 서버의
# 업로드·디코딩·저장, DB까지 한 번에 지나가므로 Dockerfile·nginx 설정·서버 실행 조건의 회귀가 종료 코드로
# 드러난다.
# [왜 이렇게 했나]
# - HTTP 요청과 multipart 본문은 5단계 부하 시험 도구(load.py)의 _req·_multipart를 그대로 쓴다(표준
#   라이브러리만, 연결 실패를 예외 대신 상태 0으로 돌려준다). 같은 코드를 두 벌 두지 않으려는 것이다.
# - 기대 답은 서버 통합 테스트(ApiIntegrationTest의 '완료 기준 2')와 같게 파일마다 표로 둔다. 상태 코드만 보지
#   않고 본문의 종목(sport)이나 오류 코드(error)도 본다. 앞단(nginx)이 낸 400처럼 상태 코드만 맞는 답을
#   통과시키면 서버 경로가 깨진 것을 놓치기 때문이다. Coros 파일은 디코더가 받아들일지가 정해져 있지
#   않아(FitDecoderTest도 두 결과를 모두 받음) 201이면 본문을 보지 않는다.
# - 실행할 때마다 새 선수를 만든다. 같은 선수에게 같은 파일을 다시 올리면 409(같은 시작 시각)가 되기 때문이다.
#   선수 삭제 API가 없으므로 만든 선수는 DB에 남는다(docs/api.md).
# [주의] 파일은 차례로 하나씩 올린다. 서버는 업로드 디코딩을 동시에 2개까지만 해서(넘치면 기다리다 429)
# 한꺼번에 올리면 데모가 기다림이나 429에 걸릴 수 있다.

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote

from ride_insight_ml import load

# 저장소 루트(ml/src/ride_insight_ml/demo.py의 세 단계 위, uv의 편집 가능 설치라 소스 위치를 가리킨다)와 샘플
# FIT 폴더. 샘플 FIT와 라이선스 파일(LICENSE-python-fitparse.txt)이 함께 커밋돼 있다.
REPO_ROOT = Path(__file__).resolve().parents[3]
FIT_DIR = REPO_ROOT / "server" / "src" / "test" / "resources" / "fit"
# 데모 선수. FTP는 생성 때 등록하면 1990-01-01부터 적용돼(서버 AthleteService.create) 2011~2020년 샘플
# 라이딩에도 IF·TSS가 계산된다. 시간대는 서버 기본값과 같은 Asia/Seoul을 명시한다.
DEMO_NAME = "데모 선수"
DEMO_FTP = 250
DEMO_TIME_ZONE = "Asia/Seoul"


@dataclass(frozen=True)
class Sample:
    """샘플 FIT 하나와 기대 답. expect는 (허용 상태 코드, 본문에서 확인할 (필드, 값) 또는 None)의 튜플이다."""

    name: str
    kind: str
    expect: tuple[tuple[int, tuple[str, str] | None], ...]

    @property
    def statuses(self) -> tuple[int, ...]:
        return tuple(status for status, _ in self.expect)


# 상태 코드별 본문 확인: 저장(201)은 종목 cycling, 잘못된 FIT(400)는 error invalid_fit, 지원하지 않는
# 종목(422)은 error unsupported_sport(docs/api.md의 업로드 응답).
_STORED = (201, ("sport", "cycling"))
_INVALID = (400, ("error", "invalid_fit"))
_UNSUPPORTED = (422, ("error", "unsupported_sport"))
SAMPLES: tuple[Sample, ...] = (
    Sample("Edge810-Vector-2013-08-16-15-35-10.fit", "cycling", (_STORED,)),
    Sample("garmin-edge-500-activity.fit", "cycling", (_STORED,)),
    Sample("garmin-edge-820-bike.fit", "cycling", (_STORED,)),
    Sample("2019-02-17-062644-ELEMNT-297E-195-0.fit", "cycling", (_STORED,)),
    Sample("sample-activity-indoor-trainer.fit", "cycling", (_STORED,)),
    Sample("garmin-fenix-5-run.fit", "running", (_UNSUPPORTED,)),
    Sample("activity-unexpected-eof.fit", "corrupt", (_INVALID,)),
    Sample("activity-settings-corruptheader.fit", "corrupt", (_INVALID,)),
    Sample("coros-pace-2-cycling-misaligned-fields.fit", "coros", ((201, None), _INVALID)),
)


# 응답 본문을 JSON 객체로 읽는다. JSON이 아니거나(예: nginx의 HTML 오류 페이지) 객체가 아니면 빈 dict.
def _json_object(body: bytes) -> dict:
    try:
        data = json.loads(body)
    except ValueError:
        return {}
    return data if isinstance(data, dict) else {}


# 출력용으로 본문 앞부분만 한 줄로 보여 준다.
def _snippet(body: bytes, limit: int = 160) -> str:
    text = body.decode("utf-8", errors="replace").replace("\n", " ")
    return text if len(text) <= limit else text[: limit - 3] + "..."


# 답 하나가 기대대로인지 본다. 기대대로면 None, 아니면 이유 문장을 돌려준다.
def check(sample: Sample, status: int, body: bytes) -> str | None:
    rules = dict(sample.expect)
    if status not in rules:
        return "expected " + " or ".join(str(s) for s in sample.statuses)
    rule = rules[status]
    if rule is None:
        return None
    field, value = rule
    got = _json_object(body).get(field)
    return None if got == value else f"expected {field} {value!r} in the body, got {got!r}"


# 명령행 진입점. 종료 코드를 돌려준다(아래 if __name__ 블록이 SystemExit로 넘긴다).
def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="python -m ride_insight_ml.demo",
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument(
        "--target",
        default="http://127.0.0.1:8081",
        help=(
            "서비스 주소. 기본은 compose 스택의 웹(nginx)이라 /api 프록시까지 지난다. 서버에 바로 보내려면 "
            "http://127.0.0.1:8080. nginx는 Host가 localhost·127.0.0.1, 서버는 거기에 [::1]까지일 때만 "
            "답한다(docs/api.md)"
        ),
    )
    p.add_argument(
        "--fit-dir",
        type=Path,
        default=FIT_DIR,
        help="샘플 FIT 9개가 있는 폴더. 기본은 저장소의 server/src/test/resources/fit",
    )
    a = p.parse_args(argv)
    target = a.target.rstrip("/")
    # 스킴이 없으면 urllib이 ValueError로 멈추므로 요청 전에 사용법 오류(종료 코드 2)로 끝낸다.
    if not target.startswith(("http://", "https://")):
        p.error(f"--target must start with http:// or https:// (got {a.target!r})")
    # 출력에 서버가 돌려준 문장이 섞인다. Windows에서 파이프로 넘긴 출력(cp949 같은 코드 페이지)에 없는 글자는
    # ?로 써서 인코딩 오류로 멈추지 않게 한다.
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        reconfigure(errors="replace")

    missing = [s.name for s in SAMPLES if not (a.fit_dir / s.name).is_file()]
    if missing:
        print(f"sample FIT files not found in {a.fit_dir}: {', '.join(missing)}")
        return 2

    athlete = json.dumps({"name": DEMO_NAME, "ftp": DEMO_FTP, "timeZone": DEMO_TIME_ZONE}).encode()
    status, _, body = load._req("POST", f"{target}/api/athletes", athlete, "application/json")
    if status == load.CONNECTION_ERROR:
        print(f"could not reach {target}: {_snippet(body)}")
        print("start the stack first (in the repository root): docker compose up --build -d --wait")
        return 1
    athlete_id = _json_object(body).get("id")
    if status != 201 or not isinstance(athlete_id, str):
        print(f"could not create the demo athlete: {status} {_snippet(body)}")
        return 1
    print(f"athlete {athlete_id} (FTP {DEMO_FTP} W, {DEMO_TIME_ZONE})")

    upload_url = f"{target}/api/athletes/{quote(athlete_id, safe='')}/activities"
    counts: Counter[int] = Counter()
    failed = 0
    dates: list[str] = []
    for sample in SAMPLES:
        payload, ctype = load._multipart(sample.name, (a.fit_dir / sample.name).read_bytes())
        status, _, body = load._req("POST", upload_url, payload, ctype)
        counts[status] += 1
        reason = check(sample, status, body)
        data = _json_object(body)
        if reason is not None:
            failed += 1
            shown = "no answer" if status == load.CONNECTION_ERROR else str(status)
            print(f"  UNEXPECTED {sample.name}: {shown} ({reason}) {_snippet(body)}")
        elif status == 201:
            started = str(data.get("startedAt", ""))
            dates.append(started[:10])
            print(f"  {status} {sample.name}  {sample.kind}, sport {data.get('sport')}, started {started}")
        else:
            print(f"  {status} {sample.name}  {sample.kind}, {data.get('error')}")
    print("status counts: " + json.dumps({str(k): v for k, v in sorted(counts.items())}))
    if failed:
        print(f"{failed} of {len(SAMPLES)} files answered differently than expected")
        return 1
    print(f"all {len(SAMPLES)} files answered as expected")
    stored = sorted(d for d in dates if d)
    if stored:
        print(
            "to see them: in the web app choose 선수 > 기존 선수 id로 전환 with the id above, and set the "
            f"dashboard date near a ride date (UTC start dates {stored[0]} .. {stored[-1]})"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
