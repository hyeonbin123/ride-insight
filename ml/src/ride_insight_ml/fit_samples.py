"""Download sample FIT files used by the server's parser tests.

Source: python-fitparse test suite (MIT licence), https://github.com/dtcooper/python-fitparse.
The files are not redistributed in this repository; run
``python -m ride_insight_ml.fit_samples --dest ../data/samples/fit``.
"""

# [파일 역할] FIT 디코더 테스트와 5번 질문(FIT 수용성)에 쓰는 샘플 FIT 9개를 python-fitparse 저장소에서
# 내려받는 스크립트다.
# [왜 이 파일들인가] 서로 다른 기기(Garmin Edge 500/810/820, Wahoo ELEMNT, Coros)와 실내 트레이너, 러닝 활동,
# 손상 파일(잘린 파일, 헤더 손상)을 고루 넣어 '받아야 할 것은 받고 거절해야 할 것은 깨끗하게 거절하는지'를
# 확인하려는 구성이다(docs/plan.md 샘플 FIT 절, docs/api.md 완료 기준 2).
# [참고] 서버 테스트는 같은 이름의 파일을 server/src/test/resources/fit/에서 읽는다(README에 python-fitparse
# 테스트 파일, MIT로 적혀 있고 라이선스 파일이 함께 있다).

from __future__ import annotations

# 외부 의존성 없이 표준 라이브러리 urllib만으로 받는다. 파일이 9개뿐이라 재시도·병렬 처리가 필요 없다.
import argparse
import sys
import urllib.request
from pathlib import Path

# BASE: python-fitparse 저장소 master 브랜치의 테스트 파일 폴더(raw 파일 주소). 파일 이름을 뒤에 붙여 받는다.
BASE = "https://raw.githubusercontent.com/dtcooper/python-fitparse/master/tests/files/"

# SAMPLES: 파일 이름 → 넣은 이유. 이유 문자열은 코드에서 쓰지 않고 사람이 읽는 설명이다. 마지막 세 줄(러닝,
# 잘린 파일, 헤더 손상)은 서버가 422 또는 400으로 거절해야 하는 입력이다.
# name -> why it is in the set
SAMPLES: dict[str, str] = {
    "garmin-edge-500-activity.fit": "Garmin Edge 500 outdoor ride",
    "Edge810-Vector-2013-08-16-15-35-10.fit": "Garmin Edge 810 with Vector power meter",
    "garmin-edge-820-bike.fit": "Garmin Edge 820 short ride",
    "2019-02-17-062644-ELEMNT-297E-195-0.fit": "Wahoo ELEMNT ride",
    "coros-pace-2-cycling-misaligned-fields.fit": "Coros Pace 2 ride with misaligned developer fields",
    "sample-activity-indoor-trainer.fit": "indoor trainer session",
    "garmin-fenix-5-run.fit": "running activity (should be rejected or flagged as non-cycling)",
    "activity-unexpected-eof.fit": "truncated file (must fail cleanly)",
    "activity-settings-corruptheader.fit": "corrupt header (must fail cleanly)",
}


# 명령행 진입점. --dest 폴더에 SAMPLES의 파일을 차례로 받는다. 이미 있는 파일은 건너뛰어, 중간에 끊겨도 같은
# 명령을 다시 돌리면 남은 것만 받는다. 받은 파일의 무결성(해시) 검사는 하지 않는다.
def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dest", type=Path, required=True)
    a = p.parse_args(argv)
    a.dest.mkdir(parents=True, exist_ok=True)
    for name in SAMPLES:
        target = a.dest / name
        if target.exists():
            continue
        urllib.request.urlretrieve(BASE + name, target)
        print(f"fetched {name}", file=sys.stderr)
    print(f"ok {len(SAMPLES)} files in {a.dest}")


if __name__ == "__main__":
    main()
