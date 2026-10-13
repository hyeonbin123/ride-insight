# [테스트 묶음] parity_sets.py(1단계 개발·봉인 선수 목록 뽑기)의 단위 테스트. 목록이 표본 순서·train 조건·파워
# 라이딩 수 조건을 지키고, 인덱스가 빠진 선수에서 멈추는지 확인한다.
# [왜 중요한가] 봉인 세트는 한 번만 재는 최종 평가 대상이라, 목록이 규칙대로(재현 가능하게) 정해졌다는 것이
# 결과의 신뢰 근거다.

import json
from pathlib import Path

from ride_insight_ml import parity_sets


# 가짜 rides 인덱스를 쓴다. 짝지은 파워 라이딩 power_rides개에 더해, CSV가 없는 파워 라이딩 1개와 파워 없는
# CSV 라이딩 1개를 넣어 둘 다 세지 않는지도 함께 시험한다.
def _index(dir_: Path, aid: str, power_rides: int):
    rides = [{"csv": f"{i}.csv", "power_flag": True} for i in range(power_rides)]
    rides.append({"csv": None, "power_flag": True})
    rides.append({"csv": "x.csv", "power_flag": False})
    (dir_ / f"{aid}.json").write_text(json.dumps({"rides": rides}), encoding="utf-8")


# 400명 중 10번째마다 dev 분할 선수, 3번째마다 파워 라이딩 10개(조건 미달) 선수를 섞는다. 결과는 개발
# 100명·봉인 100명이 겹치지 않고, dev 분할 선수(a000)와 조건 미달 선수는 빠지며, 표본 순서가 유지돼야 한다.
def test_build_keeps_sample_order_and_threshold(tmp_path: Path):
    sample = []
    for i in range(400):
        split = "dev" if i % 10 == 0 else "train"
        sample.append({"id": f"a{i:03d}", "split": split})
        _index(tmp_path, f"a{i:03d}", 150 if i % 3 else 10)
    dev, sealed, info = parity_sets.build(sample, tmp_path, 100, allow_partial=False)
    assert len(dev) == 100 and len(sealed) == 100
    assert not set(dev) & set(sealed)
    assert dev[0] == "a001"  # a000 is a dev-split athlete, so it is not eligible
    assert all(int(x[1:]) % 10 != 0 and int(x[1:]) % 3 != 0 for x in dev + sealed)
    assert dev == sorted(dev)
    assert info["complete"]


# 인덱스가 4명분만 있을 때 allow_partial이면 처음 빠진 선수에서 멈추고 그때까지의 4명만 개발 세트로 돌려주며
# complete가 False인지 확인한다. 빠진 선수를 건너뛰지 않아야 나중에 다운로드가 끝났을 때 목록이 바뀌지 않는다.
def test_partial_stops_at_first_missing_index(tmp_path: Path):
    sample = [{"id": f"a{i}", "split": "train"} for i in range(10)]
    for i in range(4):
        _index(tmp_path, f"a{i}", 120)
    dev, sealed, info = parity_sets.build(sample, tmp_path, 100, allow_partial=True)
    assert dev == ["a0", "a1", "a2", "a3"] and sealed == []
    assert not info["complete"]
