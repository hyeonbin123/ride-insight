"""LightGBM FTP 모델을 `dump_model()` JSON으로 내보내고 Kotlin 테스트용 기준 예측을 함께 쓴다.

사용법:
  python -m ride_insight_ml.ftp_export --units <units.parquet> --variant M1
      --out-model <model.json> --out-cases <cases.json>
  python -m ride_insight_ml.ftp_export --synthetic --out-model ... --out-cases ...   (단위 테스트 고정 자료)

사례 파일에는 특징 행 최대 100개(있으면 dev 분할)와 그 Python 예측이 들어 있다.
`LightGbmModelTest`가 이것으로 Kotlin 트리 순회 추론기가 그 예측을 1e-6 안에서 재현하는지 확인한다.
"""

# [파일 역할] 학습한 LightGBM FTP 모델을 dump_model() JSON으로 내보내고, 같은 입력에 대한 Python 예측을 '정답
# 사례'로 함께 저장한다. Kotlin의 LightGbmModel(트리 직접 순회 추론기)이 이 사례를 1e-6 안에서 재현하는지
# LightGbmModelTest가 확인한다.
# [왜 필요한가] 3단계 규칙은 'LightGBM이 채택되면 dump_model() JSON을 Kotlin에서 직접 순회해 추론한다(새
# 의존성 없음)'고 미리 정했다(docs/experiments.md 서비스 반영). 직접 쓴 추론기가 원본 라이브러리와 같은 값을
# 내는지는 외부 기준(Python 예측)과 대조해야만 알 수 있다.
# [두 가지 모드] --synthetic: 결측값이 섞인 작은 합성 모델(테스트 고정 자료
# server/src/test/resources/lgbm/tiny_*.json). 기본 모드: units.parquet의 train으로 M1(또는 M1b)을 학습하고
# dev 단위로 사례를 만든다. H5가 판정 불가라 실제 모델은 서비스에 넣지 않았고, ftp_model.json이 없으면 Kotlin
# 테스트는 그 사례를 건너뛴다.

from __future__ import annotations

import argparse
import json
from pathlib import Path

# numpy: 합성 데이터 생성(seed 0 Generator). pandas: 특징 행렬과 단위 표.
# ftp 모듈에서 하이퍼파라미터(LGBM_PARAMS)와 특징 행렬 함수(_matrix)를 그대로 가져와, 실험에서 평가한 모델과
# 같은 설정·같은 열 순서로 내보낸다.
import numpy as np
import pandas as pd

from ride_insight_ml import ftp


# 모델 JSON과 대조 사례 JSON을 쓴다. 호출 위치: main(두 모드 모두).
# - out_model: booster_.dump_model()의 결과(트리 구조, 분기 기준값, 결측 방향, 잎 값). Kotlin 추론기가 읽는
#   형식이다.
# - out_cases: feature_names(열 순서)와 앞 n_cases(기본 100)행의 특징 값·Python 예측. 결측은 null로 써서
#   Kotlin 쪽 결측 분기도 검사되게 한다(규칙: dev 100개 단위에서 1e-6 안 일치).
def _write(model, x: pd.DataFrame, out_model: Path, out_cases: Path, n_cases: int = 100) -> None:
    dump = model.booster_.dump_model()
    out_model.parent.mkdir(parents=True, exist_ok=True)
    out_model.write_text(json.dumps(dump), encoding="utf-8")
    sample = x.head(n_cases)
    preds = model.predict(sample)
    cases = []
    for (_, row), p in zip(sample.iterrows(), preds, strict=True):
        cases.append(
            {
                "features": {k: (None if pd.isna(v) else float(v)) for k, v in row.items()},
                "prediction": float(p),
            }
        )
    out_cases.parent.mkdir(parents=True, exist_ok=True)
    out_cases.write_text(json.dumps({"feature_names": list(x.columns), "cases": cases}), encoding="utf-8")
    print(f"trees={len(dump['tree_info'])} features={len(dump['feature_names'])} cases={len(cases)}")


# 명령행 진입점. --synthetic이면 합성 모델을, 아니면 --units의 실제 단위로 --variant(M1/M1b) 모델을 학습해
# --out-model, --out-cases에 쓴다.
def main(argv: list[str] | None = None) -> None:
    # lightgbm은 이 명령을 실제로 실행할 때만 필요하므로 함수 안에서 불러온다.
    import lightgbm as lgb

    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--units", type=Path)
    p.add_argument("--variant", choices=["M1", "M1b"], default="M1")
    p.add_argument("--synthetic", action="store_true")
    p.add_argument("--out-model", type=Path, required=True)
    p.add_argument("--out-cases", type=Path, required=True)
    a = p.parse_args(argv)

    # 합성 모드: 특징 3개(a, b, c), 400행. c의 약 10%를 NaN으로 만들어 트리의 결측값 분기 경로를 시험한다.
    # 정답 y는 단순한 선형 식 + 작은 잡음이고, 모델은 작게(트리 30개, 잎 7개) 만든다. 결과는 테스트
    # 리소스(tiny_model.json, tiny_cases.json)로 커밋된다.
    if a.synthetic:
        rng = np.random.default_rng(0)
        n = 400
        x = pd.DataFrame({"a": rng.normal(size=n), "b": rng.uniform(size=n), "c": rng.normal(size=n)})
        x.loc[rng.random(n) < 0.1, "c"] = np.nan  # 결측값으로 NaN 분기를 시험한다
        y = 3 * x["a"] + 2 * x["b"] + x["c"].fillna(0) + rng.normal(scale=0.1, size=n)
        model = lgb.LGBMRegressor(
            n_estimators=30, num_leaves=7, learning_rate=0.2, min_child_samples=5, verbose=-1, random_state=0
        )
        model.fit(x, y)
        _write(model, x, a.out_model, a.out_cases)
        return

    # 실제 모드: ftp.evaluate와 같은 조건(20분 MMP가 있는 train 단위, M1b면 체중이 있는 단위만)으로 학습하고,
    # 대조 사례는 dev 단위에서 뽑는다(규칙의 'dev 100개 단위').
    units = pd.read_parquet(a.units)
    train = units[(units["split"] == "train") & units["mmp_20m"].notna()]
    with_weight = a.variant == "M1b"
    if with_weight:
        train = train[train["weight"].notna()]
    model = lgb.LGBMRegressor(**ftp.LGBM_PARAMS)
    xt = ftp._matrix(train, with_weight)
    model.fit(xt, train["label_ftp"])
    dev = units[(units["split"] == "dev") & units["mmp_20m"].notna()]
    if with_weight:
        dev = dev[dev["weight"].notna()]
    _write(model, ftp._matrix(dev, with_weight), a.out_model, a.out_cases)


if __name__ == "__main__":
    main()
