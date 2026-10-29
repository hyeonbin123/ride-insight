"""서버의 FIT 파서 테스트에 쓰는 샘플 FIT 파일을 내려받는다.

출처: python-fitparse 테스트 모음(MIT 라이선스), https://github.com/dtcooper/python-fitparse.
같은 파일의 사본이 MIT 라이선스 전문과 함께 서버 테스트용으로 ``server/src/test/resources/fit/``에
커밋돼 있다. 이 스크립트는 그 파일들을 데이터 폴더로 다시 받는다:
``python -m ride_insight_ml.fit_samples --dest ../data/samples/fit``.

받는 주소는 python-fitparse의 특정 커밋에 고정돼 있고, 파일마다 커밋된 사본에서 계산한 SHA-256과
비교한다. 해시가 다르거나 받지 못한 파일이 하나라도 있으면 그 파일을 남기지 않고 종료 코드 2로 끝난다.
"""

# [파일 역할] FIT 디코더 테스트와 5번 질문(FIT 수용성)에 쓰는 샘플 FIT 9개를 python-fitparse 저장소에서
# 내려받는 스크립트다.
# [왜 이 파일들인가] 서로 다른 기기(Garmin Edge 500/810/820, Wahoo ELEMNT, Coros)와 실내 트레이너, 러닝 활동,
# 손상 파일(잘린 파일, 헤더 손상)을 고루 넣어 '받아야 할 것은 받고 거절해야 할 것은 깨끗하게 거절하는지'를
# 확인하려는 구성이다(docs/plan.md 샘플 FIT 절, docs/api.md 완료 기준 2).
# [참고] 서버 테스트는 같은 이름의 파일을 server/src/test/resources/fit/에서 읽는다. 그 파일들은 MIT 라이선스
# 문서(LICENSE-python-fitparse.txt)와 함께 커밋돼 있다(README 실행 절). 예전 docstring은 '재배포하지 않는다'고
# 적어 실제와 달랐다.
# [무결성] 예전에는 master 브랜치에서 해시 확인 없이 받았다. 서버 테스트는 커밋된 사본을 쓰므로 CI에는 영향이
# 없었지만, 이 스크립트로 다시 받은 파일로 기대값(fit_expected.py)을 다시 만들면 업스트림 변경이나 잘못 받은
# 파일이 그대로 테스트 기준이 될 수 있었다. 그래서 주소를 커밋에 고정하고(COMMIT) 파일별 SHA-256(SAMPLES)을
# opendata.py의 download와 같은 방식(.part로 받아 해시가 맞을 때만 최종 이름으로 바꿈)으로 검증한다.

from __future__ import annotations

# 외부 의존성 없이 표준 라이브러리와 opendata.py의 내려받기 함수(fetch_with_sha256: urlopen + timeout,
# 받으면서 SHA-256 계산)만 쓴다. 파일이 9개뿐이고 작아서(합계 약 1.5 MB) 재시도·병렬 처리는 두지 않았다.
# 실패하면 같은 명령을 다시 돌리면 이미 검증된 파일은 건너뛰고 남은 것만 받는다.
import argparse
import hashlib
import sys
from pathlib import Path
from typing import NamedTuple

from ride_insight_ml.opendata import fetch_with_sha256

# COMMIT: python-fitparse 저장소의 고정 커밋(2025-01-28 master, PR #147 병합). 이 커밋의 tests/files/에 있는
# 아래 9개 파일의 git blob SHA-1이 저장소에 커밋된 사본(server/src/test/resources/fit/)의 `git hash-object`
# 값과 모두 같음을 GitHub API로 확인했다(2026-10-29). 커밋은 SHA라 내용이 바뀌지 않는다.
# BASE: 그 커밋의 테스트 파일 폴더(raw 파일 주소). 파일 이름을 뒤에 붙여 받는다.
COMMIT = "d88bb6997138c38d0c578aeaaf4108df0711d1f6"
BASE = f"https://raw.githubusercontent.com/dtcooper/python-fitparse/{COMMIT}/tests/files/"

# TIMEOUT_S: urlopen 소켓 타임아웃(초). 연결하거나 한 번 읽을 때 이 시간 동안 아무것도 오지 않으면 끊는다.
# 예전에는 urlretrieve를 timeout 없이 써서 멎은 연결에서 무한히 기다릴 수 있었다. 가장 큰 파일도 약 716 KB라
# 60초면 충분하다.
TIMEOUT_S = 60


# 샘플 한 개의 기대값. sha256은 커밋된 사본(server/src/test/resources/fit/)에서 계산한 SHA-256 16진 문자열,
# why는 이 묶음에 넣은 이유(코드에서 쓰지 않는 사람용 설명)다.
class Sample(NamedTuple):
    sha256: str
    why: str


# SAMPLES: 파일 이름 → Sample. 마지막 세 줄(러닝, 잘린 파일, 헤더 손상)은 서버가 422 또는 400으로 거절해야
# 하는 입력이다. 해시가 커밋된 사본과 같은지는 tests/test_fit_samples.py가 확인한다(픽스처를 바꾸면 여기도
# 함께 고쳐야 그 테스트가 통과한다).
SAMPLES: dict[str, Sample] = {
    "garmin-edge-500-activity.fit": Sample(
        "656b13b54ef57251503a050fa6c1c1f38e1374ba90aa414f9901cd9f1f6d383e", "Garmin Edge 500 outdoor ride"
    ),
    "Edge810-Vector-2013-08-16-15-35-10.fit": Sample(
        "e278d20809b1fda62f4b60dfe291a4b16f00933ad37e923a99e5a59bb48f6eab",
        "Garmin Edge 810 with Vector power meter",
    ),
    "garmin-edge-820-bike.fit": Sample(
        "ae4332ceec567ede98cb2cbb84b735a2f367908b7881ae15783acb2ccca6a8ec", "Garmin Edge 820 short ride"
    ),
    "2019-02-17-062644-ELEMNT-297E-195-0.fit": Sample(
        "3f73dc39741c63f3883e2db9f80447f421a3845e7313d900a8ec66629b49a656", "Wahoo ELEMNT ride"
    ),
    "coros-pace-2-cycling-misaligned-fields.fit": Sample(
        "1a9c2923f7bc66fadfb812bb9cdf67891b0eb912cfba4da0a55f0e5d423a691d",
        "Coros Pace 2 ride with misaligned developer fields",
    ),
    "sample-activity-indoor-trainer.fit": Sample(
        "1ec64427b0feaa8031e0832426164c1266ae5db939d0f45fcd00cd198c33f0b7", "indoor trainer session"
    ),
    "garmin-fenix-5-run.fit": Sample(
        "db12d0986f3b707802480d6a79bc6bf5d3061010ffe98a1b99115dd44e75f5be",
        "running activity (should be rejected or flagged as non-cycling)",
    ),
    "activity-unexpected-eof.fit": Sample(
        "0f0ed028e09aa3efb757c1e2e31b36a4225acdd579f049918b59cdf9ab049b46",
        "truncated file (must fail cleanly)",
    ),
    "activity-settings-corruptheader.fit": Sample(
        "12c9b5bff880497f5bf2bf7235d50f4f632695384c96828dc500685d99c7c032",
        "corrupt header (must fail cleanly)",
    ),
}


# 파일의 SHA-256 16진 문자열. 샘플은 가장 커도 약 716 KB라 한 번에 읽는다.
def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


# 명령행 진입점. --dest 폴더에 SAMPLES의 파일을 차례로 받는다.
# - 이미 있고 해시가 맞으면 건너뛴다. 해시가 다른 기존 파일(다른 커밋에서 받은 옛 사본 등)은 .bad로 이름을
#   바꿔 비켜 둔 뒤 다시 받는다(opendata.py download와 같은 처리). 예전에는 이름만 있으면 건너뛰었다.
# - .part로 받은 뒤 해시가 맞을 때만 최종 이름으로 바꾼다. 해시가 다르거나 받다 실패하면 .part를 지우고
#   그 파일을 failed로 센다. 하나라도 failed면 종료 코드 2로 끝내 '일부만 맞는 상태'를 성공으로 오인하지 않게
#   한다.
# 출력: 파일마다 stderr 한 줄, 마지막에 stdout 요약 한 줄(fetched·skipped·failed 수).
def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument(
        "--dest",
        type=Path,
        required=True,
        help="샘플 FIT를 받을 폴더(예: ../data/samples/fit). 없으면 만든다. 이미 있는 파일은 해시로 확인한다",
    )
    a = p.parse_args(argv)
    a.dest.mkdir(parents=True, exist_ok=True)
    fetched = skipped = failed = 0
    for name, sample in SAMPLES.items():
        target = a.dest / name
        if target.exists():
            if _sha256(target) == sample.sha256:
                skipped += 1
                continue
            stale = target.with_suffix(".bad")
            target.replace(stale)
            print(f"stale file moved to {stale.name}", file=sys.stderr)
        tmp = target.with_suffix(".part")
        try:
            digest = fetch_with_sha256(BASE + name, tmp, TIMEOUT_S)
        except Exception as e:  # noqa: BLE001 - 어떤 네트워크 오류든 이 파일의 실패로 세고 다음 파일로 간다
            tmp.unlink(missing_ok=True)
            failed += 1
            print(f"download failed: {name}: {e!r}", file=sys.stderr)
            continue
        if digest != sample.sha256:
            tmp.unlink(missing_ok=True)
            failed += 1
            print(f"sha256 mismatch: {name}", file=sys.stderr)
            continue
        tmp.replace(target)
        fetched += 1
        print(f"fetched {name}", file=sys.stderr)
    print(f"fetched={fetched} skipped={skipped} failed={failed} in {a.dest}")
    if failed:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
