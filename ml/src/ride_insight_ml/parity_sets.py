"""Build the stage-1 parity athlete lists (docs/experiments.md).

Rule: walk ``manifests/opendata_sample.json`` in sample order, keep train athletes whose ride index
has at least ``--min-power-rides`` matched power rides; the first 100 are the development set and
the next 100 the sealed set. Athletes whose zip or index is missing stop the walk (``--allow-partial``
writes what is available so far and reports how many are still needed).

Usage: ``python -m ride_insight_ml.parity_sets --sample ../manifests/opendata_sample.json
--index ../data/opendata_index --out-dir ../manifests``
"""

# [파일 역할] 1단계 파리티 측정에 쓸 선수 목록 두 개(개발 세트 manifests/parity_dev.txt, 봉인 세트
# manifests/parity_sealed.txt)를 규칙대로 뽑는다.
# [설계 이유] 개발 세트로 구현을 맞추고 봉인 세트는 단계 끝에 한 번만 잰다(docs/experiments.md 공통 규칙).
# 누가 언제 돌려도 같은 목록이 나와야 하므로 무작위 대신 표본 매니페스트의 순서(SHA-256 순서)를 그대로
# 따라가며 조건을 만족하는 선수를 앞에서부터 채운다. 지표 정확성 검증은 학습이 아니라 구현 확인이라 train
# 선수만 쓰고 dev·test 선수는 FTP 실험을 위해 남겨 둔다(docs/plan.md).
# [조건] 짝지어진 파워 라이딩 수 ≥ --min-power-rides. 기본값은 규칙 변경 1(표본 수율 37%라 200명을 못 채울
# 것으로 추정)대로 50이다. 커밋된 목록도 50으로 뽑았다. 처음에는 기본값이 규칙 변경 전 값 100으로 남아 있어,
# 인자 없이 다시 돌리면 커밋된 목록과 다른 목록이 나왔다(2026-10-13 수정).

from __future__ import annotations

import argparse
import json
from pathlib import Path

# 개발 세트와 봉인 세트의 선수 수(각 100명, docs/experiments.md 1단계 데이터).
DEV_SIZE = 100
SEALED_SIZE = 100


# rides.py 인덱스 JSON에서 'CSV와 짝지어졌고(csv가 있음) 파워 플래그가 있는' 라이딩 수를 센다. 짝짓지 못했거나
# 모호한 라이딩(csv가 null)은 GC 값과 비교할 수 없어 세지 않는다.
def matched_power_rides(index_file: Path) -> int:
    d = json.loads(index_file.read_text(encoding="utf-8"))
    return sum(1 for r in d["rides"] if r["csv"] and r["power_flag"])


# 표본 순서대로 train 선수를 훑어 조건을 만족하는 선수를 200명까지 모으고, 앞 100명을 개발 세트, 다음 100명을
# 봉인 세트로 돌려준다. info에는 훑은 train 선수 수, 조건 미달 수, 다 채웠는지(complete), 건너뛴 다운로드 불가
# 선수를 담는다. 호출 위치: main, tests/test_parity_sets.py.
# 인덱스가 없는 선수를 만나면 건너뛰지 않고 멈추거나(allow_partial) 오류를 낸다. 건너뛰면 나중에 그 선수가
# 받아졌을 때 목록이 바뀌기 때문이다. 즉 목록은 다운로드 진행 상황이 아니라 표본 순서에만 좌우된다. 재시도해도
# 끝내 받지 못한 선수(unavailable)만 예외로 건너뛰고 기록한다(그 앞의 선택은 바뀌지 않는다).
def build(
    sample: list[dict],
    index_dir: Path,
    min_power_rides: int,
    allow_partial: bool,
    unavailable: set[str] | None = None,
) -> tuple[list, list, dict]:
    """``unavailable``: athletes whose zip could not be downloaded after retries; they are skipped in
    sample order (recorded in the result), which cannot change the picks before them."""
    qualifying: list[str] = []
    seen = skipped = 0
    skipped_unavailable: list[str] = []
    for row in sample:
        if row["split"] != "train":
            continue
        idx = index_dir / f"{row['id']}.json"
        if not idx.exists():
            if unavailable and row["id"] in unavailable:
                skipped_unavailable.append(row["id"])
                continue
            if allow_partial:
                break
            raise FileNotFoundError(f"index missing for {row['id']} (download and index it first)")
        seen += 1
        if matched_power_rides(idx) >= min_power_rides:
            qualifying.append(row["id"])
        else:
            skipped += 1
        # 200명이 차면 더 훑지 않는다. 그 뒤 선수의 인덱스가 없어도 목록에는 영향이 없다.
        if len(qualifying) >= DEV_SIZE + SEALED_SIZE:
            break
    dev = qualifying[:DEV_SIZE]
    sealed = qualifying[DEV_SIZE : DEV_SIZE + SEALED_SIZE]
    info = {
        "train_seen": seen,
        "below_threshold": skipped,
        "qualifying": len(qualifying),
        "complete": len(qualifying) >= DEV_SIZE + SEALED_SIZE,
        "skipped_unavailable": skipped_unavailable,
    }
    return dev, sealed, info


# 명령행 진입점. --sample(표본 매니페스트), --index(rides 인덱스 폴더), --out-dir(목록을 쓸 폴더)를 받는다.
# --unavailable은 다운로드 불가 선수 id 파일(한 줄에 하나, manifests/opendata_unavailable.txt). 결과 목록은 한
# 줄에 선수 id 하나인 텍스트 파일로, Kotlin parity 도구의 --athletes 입력 형식과 같다.
def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--sample", type=Path, required=True)
    p.add_argument("--index", type=Path, required=True)
    p.add_argument("--out-dir", type=Path, required=True)
    # 규칙 변경 1(docs/experiments.md): 100 -> 50.
    p.add_argument("--min-power-rides", type=int, default=50)
    p.add_argument("--allow-partial", action="store_true")
    p.add_argument(
        "--unavailable", type=Path, default=None, help="file with ids whose zip could not be downloaded"
    )
    a = p.parse_args(argv)
    sample = json.loads(a.sample.read_text(encoding="utf-8"))
    unavailable = None
    if a.unavailable:
        unavailable = {x.strip() for x in a.unavailable.read_text(encoding="utf-8").splitlines() if x.strip()}
    dev, sealed, info = build(sample, a.index, a.min_power_rides, a.allow_partial, unavailable)
    a.out_dir.mkdir(parents=True, exist_ok=True)
    (a.out_dir / "parity_dev.txt").write_text("\n".join(dev) + ("\n" if dev else ""), encoding="utf-8")
    (a.out_dir / "parity_sealed.txt").write_text(
        "\n".join(sealed) + ("\n" if sealed else ""), encoding="utf-8"
    )
    print(json.dumps({**info, "dev": len(dev), "sealed": len(sealed)}))


if __name__ == "__main__":
    main()
