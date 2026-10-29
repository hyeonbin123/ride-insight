# [테스트 묶음] ftp.py(3단계 FTP 추정 실험)의 단위 테스트. 실제 OpenData 없이 '정답을 아는' 합성 라이딩으로
# 예측 단위 만들기(기준일, 라벨, 신선도, 분할), 규칙 후보(R1, R3), 평가 전체 흐름, 선수 단위 부트스트랩이
# 의도대로 동작하는지 확인한다.
# [왜 중요한가] 봉인 test는 한 번만 재므로 라벨·신선도·부트스트랩 정의가 틀리면 다시 잴 기회가 없다. 실제로
# 신선도 정의가 등록 규칙과 달랐던 버그(규칙 변경 5)를 고친 뒤 경계 테스트를 여기
# 추가했다(docs/ai-verification.md).

import datetime as dt
import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from ride_insight_ml import ftp


# 합성 라이딩 한 줄(Kotlin parity JSONL 형식)을 만든다. MMP 곡선은 FTP × (1 + 2 / (1 + t/60))로 짧을수록 높고
# 길수록 FTP에 가까워지는 단순한 모양이다(20분이면 약 1.095 × FTP). GC 값은 NP = 0.8 × FTP, IF = 0.8로 둬서
# 역산 임계값 NP / IF가 정확히 FTP(=L_FTP)가 되고, cp_setting은 FTP + 10이라 L_CP와 L_FTP가 구분된다.
def _ride(athlete, day, ftp_w, np_w=None):
    """최대 평균 파워가 선수의 FTP에 비례하는 단순한 곡선을 따르는 라이딩 하나."""
    secs = {
        "1s": 1,
        "5s": 5,
        "10s": 10,
        "15s": 15,
        "20s": 20,
        "30s": 30,
        "1m": 60,
        "2m": 120,
        "3m": 180,
        "5m": 300,
        "8m": 480,
        "10m": 600,
        "20m": 1200,
        "30m": 1800,
        "60m": 3600,
        "90m": 5400,
    }
    mmp = {f"{k}_critical_power": ftp_w * (1 + 2.0 / (1 + t / 60)) for k, t in secs.items()}
    return {
        "athlete": athlete,
        "date": (dt.datetime(2020, 1, 1) + dt.timedelta(days=day)).strftime("%Y/%m/%d %H:%M:%S") + " UTC",
        "rec_int": 1.0,
        "samples": 3600,
        "cp": ftp_w + 10,
        "weight": 70.0,
        "ours": {"np": np_w or ftp_w * 0.8, "mmp": mmp},
        "gc": {"np": ftp_w * 0.8, "if": 0.8},
    }


# 선수 12명의 합성 데이터 세트를 tmp_path에 쓴다: 라이딩 JSONL(3일마다 라이딩, 기본 FTP 200 + 10 × i W,
# 100일째에 20 W 오름), 선수 인덱스 JSON(성별 번갈아, 출생 연도 1985), 표본 매니페스트(앞 8명 train, 2명 dev,
# 2명 test). 100일째의 FTP 상승이 신선도 판정의 재료다. rng는 만들었다가 쓰지 않고 지운다(남은 흔적).
def _write_fixture(tmp_path: Path, n_athletes=12):
    rows = []
    rng = np.random.default_rng(1)
    for i in range(n_athletes):
        base = 200 + 10 * i
        for day in range(0, 200, 3):
            w = base + (20 if day >= 100 else 0)  # 100일째에 FTP가 한 단계 오른다
            rows.append(_ride(f"a{i}", day, w))
    jsonl = tmp_path / "all.jsonl"
    jsonl.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    index = tmp_path / "index"
    index.mkdir()
    for i in range(n_athletes):
        (index / f"a{i}.json").write_text(
            json.dumps({"gender": "M" if i % 2 else "F", "yob": "1985"}), encoding="utf-8"
        )
    sample = tmp_path / "sample.json"
    sample.write_text(
        json.dumps(
            [
                {"id": f"a{i}", "split": "train" if i < 8 else ("dev" if i < 10 else "test")}
                for i in range(n_athletes)
            ]
        ),
        encoding="utf-8",
    )
    del rng
    return jsonl, index, sample


# 기준일이 '첫 라이딩, 그다음은 직전 기준일 + 28일 이후의 첫 라이딩'으로 뽑히는지 확인한다. 1/10(9일 뒤)과
# 2/1(3일 뒤)은 건너뛰어야 한다.
def test_reference_dates_step_every_28_days():
    dates = pd.Series(pd.to_datetime(["2020-01-01", "2020-01-10", "2020-01-29", "2020-02-01", "2020-03-15"]))
    refs = ftp.reference_dates(dates)
    assert [d.date().isoformat() for d in refs] == ["2020-01-01", "2020-01-29", "2020-03-15"]


# 예측 단위의 라벨·보조 라벨·신선도·나이·창 크기·분할을 확인한다. 라벨은 창의 마지막 라이딩의 NP / IF여야
# 하므로 FTP 상승 전 200, 뒤 220이 나와야 하고, L_CP는 cp_setting(210)이어야 한다. 첫 단위는 ±28일 안에 라벨
# 변화가 없어 신선하지 않고, 상승 시점 근처 단위는 신선해야 한다. 라벨 정의가 어긋나면 3단계 전체 수치가
# 무의미해지므로 가장 기본이 되는 검사다.
def test_build_units_labels_and_freshness(tmp_path: Path):
    jsonl, index, sample = _write_fixture(tmp_path)
    rides = ftp.load_rides(jsonl)
    splits = {r["id"]: r["split"] for r in json.loads(sample.read_text())}
    athletes = {f"a{i}": {"gender": "M", "yob": "1985"} for i in range(12)}
    units = ftp.build_units(rides, athletes, splits)
    assert len(units) > 0
    a0 = units[units["athlete"] == "a0"].sort_values("ref_date")
    # 라벨은 창의 마지막 라이딩의 NP / IF다: 100일째 전에는 200, 뒤에는 220.
    assert a0.iloc[0]["label_ftp"] == 200
    assert a0.iloc[-1]["label_ftp"] == 220
    assert a0.iloc[0]["label_cp"] == 210
    # 창이 FTP 상승 직후에 끝나는 단위는 "신선"하고, 첫 단위는 신선하지 않다.
    assert not a0.iloc[0]["fresh"]
    assert a0["fresh"].any()
    assert a0.iloc[0]["age"] == 35
    assert a0.iloc[0]["n_rides"] >= ftp.MIN_RIDES
    assert set(units["split"]) == {"train", "dev", "test"}


# 신선도 정의의 경계를 날짜별로 고정한다. 라벨이 100일째에 바뀔 때 기준일 84일(16일 뒤 변화)·112일(12일 전
# 변화)은 신선, 28일(72일 뒤)·168일은 신선하지 않아야 한다. 처음 구현은 −28일 값과 +28일 이후 첫 값을 비교해
# +45일 변화도 신선으로 셌다. Codex 3차 검토가 이를 잡았고(규칙 변경 5), 이 테스트가 같은 회귀를 막는다.
def test_freshness_follows_the_registered_definition(tmp_path: Path):
    # 라벨은 100일째까지 250, 그 뒤 270이다. 기준일 ±28일 안의 라이딩 중 라벨이 다른 것이 있을 때만
    # 그 단위가 신선하다.
    rows = [_ride("a", d, 250 if d < 100 else 270) for d in range(0, 200, 2)]
    jsonl = tmp_path / "r.jsonl"
    jsonl.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    rides = ftp.load_rides(jsonl)
    units = ftp.build_units(rides, {"a": {"gender": "M", "yob": "1990"}}, {"a": "train"})
    by_day = {
        int((pd.Timestamp(u.ref_date) - pd.Timestamp("2020-01-01")).days): bool(u.fresh)
        for u in units.itertuples()
    }
    assert by_day[84] is True  # 100일째의 변화가 16일 뒤에 있다
    assert by_day[28] is False  # 변화가 72일 뒤에 있다
    assert by_day[112] is True  # 변화가 12일 전에 있었다
    assert by_day[168] is False


# 특성화 테스트(지금 동작을 고정): 특징 days_since_last('마지막 라이딩까지 일수')는 구성상 모든 예측 단위에서
# 0이다. 기준일(reference_dates)이 선수의 실제 라이딩 시각이고 창이 (기준일 − 42일, 기준일]이라, 창의 마지막
# 라이딩이 곧 기준 라이딩이기 때문이다. 그래서 라이딩 사이에 1~9일 간격이 있고 시작 시각이 제각각이어도 값은
# 0이고, M1·M2의 입력 23열 중 실질 특징은 22개다(docs/experiments.md 규칙 변경 15). 3단계는 이 정의로 측정을
# 마쳤으므로 FEATURES와 단위 생성 코드는 바꾸지 않는다. 다음 측정 규칙에 먼저 등록한 뒤 다시 정의하거나
# (예: 기준 라이딩 직전 라이딩까지 일수) 열을 빼면 이 테스트는 의도적으로 바뀐다.
def test_days_since_last_is_zero_in_every_unit_by_construction(tmp_path: Path):
    gaps = [1, 4, 2, 9, 3, 7, 1, 5, 2, 6]  # 라이딩 사이 일수(평균 4일이라 42일 창에 약 10개)
    rows, day = [], 0
    for i in range(60):
        r = _ride("a", day, 250)
        # 시작 시각도 06시~19시로 바꿔 가며 둔다(같은 날짜끼리 비교해서 0이 되는 것이 아님을 보이려고).
        start = dt.datetime(2020, 1, 1, 6) + dt.timedelta(days=day, hours=(5 * i) % 14)
        r["date"] = start.strftime("%Y/%m/%d %H:%M:%S") + " UTC"
        rows.append(r)
        day += gaps[i % len(gaps)]
    jsonl = tmp_path / "gaps.jsonl"
    jsonl.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    rides = ftp.load_rides(jsonl)
    # 입력에는 며칠씩 비는 간격이 실제로 있다(매일 탄 데이터라서 0이 나오는 것이 아니다).
    assert rides["date"].diff().dt.days.max() >= 7
    units = ftp.build_units(rides, {"a": {"gender": "M", "yob": "1990"}}, {"a": "train"})
    assert len(units) >= 5
    # 기준일은 모두 실제 라이딩의 시작 시각이다 → 창의 마지막 라이딩 = 기준 라이딩.
    assert set(ftp.reference_dates(rides["date"])) <= set(rides["date"])
    assert (units["days_since_last"] == 0.0).all()
    # 등록한 기본 특징 23개에는 그대로 들어 있다(빼는 것도 측정 코드 변경이라 다음 측정 규칙에서 정한다).
    assert "days_since_last" in ftp.FEATURES and len(ftp.FEATURES) == 23


# 규칙 후보를 손으로 아는 값으로 확인한다. R1 = 0.95 × 300 = 285, 20분 기록이 없으면 NaN. R3(CP 적합)는 20분
# 최대보다 낮고 60분 최대보다 높은 범위에 있어야 하고, 3·5·8·10·20분 중 값이 3개 미만이면(두 번째 행)
# NaN이어야 한다.
def test_rules_on_known_curve():
    u = pd.DataFrame(
        {
            "mmp_20m": [300.0, np.nan],
            "mmp_60m": [280.0, 250.0],
            "mmp_3m": [400.0, 400.0],
            "mmp_5m": [360.0, 360.0],
            "mmp_8m": [330.0, np.nan],
            "mmp_10m": [320.0, np.nan],
            "label_ftp": [285.0, 250.0],
            "athlete": ["a", "b"],
        }
    )
    assert ftp.rule_r1(u).iloc[0] == 285.0
    assert np.isnan(ftp.rule_r1(u).iloc[1])
    r3 = ftp.rule_r3(u)
    assert 250 < r3.iloc[0] < 320  # 여기서 CP는 20분 최대보다 낮고 60분 최대보다 높다
    assert np.isnan(r3.iloc[1])  # 값이 있는 구간이 3개 미만


# build → evaluate(dev, 채택 R1) → Markdown까지 한 번에 돌려 흐름이 끊기지 않는지 본다. 합성 곡선의 20분
# 최대가 1.095 × FTP라 R1(0.95배)은 약 4% 과대 추정이 되므로 편향이 양수이고 H6이 '지지'여야 한다(판정 방향
# 검사). train 단위가 적어 모델 후보는 min_train(50) 때문에 건너뛸 수 있으므로, 돌았으면 적용 비율이 (0, 1]
# 안인지만 확인한다. 반대로 규칙 R1은 학습이 필요 없어 데이터가 작아도 항상 평가돼야 한다(규칙에도 최소 학습
# 수를 적용해 R1이 빠지고 KeyError가 나던 실수, docs/ai-verification.md 3단계).
def test_evaluate_runs_end_to_end(tmp_path: Path):
    jsonl, index, sample = _write_fixture(tmp_path)
    out = tmp_path / "units.parquet"
    ftp.cmd_build(jsonl, index, sample, out)
    units = pd.read_parquet(out)
    r = ftp.evaluate(units, "dev", adopt="R1")
    assert r["n_units"] > 0
    assert "R1" in r["methods"] and r["methods"]["R1"]["mae"]["point"] is not None
    # 합성 곡선의 20분 최대는 1.095 × FTP라 그 0.95배는 약 4% 과대 추정이 된다.
    assert r["methods"]["R1"]["bias"]["point"] > 0
    assert r["H6"]["verdict"] == "지지"
    md = ftp.to_markdown(r)
    assert "R1" in md and "H6" in md
    # 학습 데이터가 이렇게 적으면 LightGBM은 건너뛸 수 있다. 실제로 돈 후보는 적용 비율 값이 있어야 한다.
    for m in r["methods"].values():
        assert "skipped" in m or 0 < m["coverage"] <= 1


# 부트스트랩이 단위가 아니라 선수를 다시 뽑는지 확인한다. 선수 2명(오차 1과 9, 각 50개)이면 선수 단위 재표집
# 평균은 1, 5, 9 중 하나라 구간이 정확히 [1, 9]가 된다. 단위를 하나씩 뽑았다면 평균이 5 근처에 몰려 구간이
# 훨씬 좁게 나왔을 것이다.
def test_athlete_bootstrap_uses_athlete_resampling():
    err = pd.Series([1.0] * 50 + [9.0] * 50)
    ath = pd.Series(["a"] * 50 + ["b"] * 50)
    r = ftp.athlete_bootstrap(err, ath, np.mean, n=500)
    assert r["point"] == 5.0
    assert r["n_athletes"] == 2
    # 선수가 둘이면 재표집 평균은 1, 5, 9 중 하나다: 구간이 양 끝에 모두 닿아야 한다.
    assert r["ci"][0] == 1.0 and r["ci"][1] == 9.0


# H7 하한(규칙 변경 10): 신선 집단이 H7_MIN_UNITS(20)개보다 하나 적으면 구간 없이 판정 불가, 정확히 20개면
# 구간을 낸다. 묵은 라벨 오차가 신선보다 10 W 크게 만들어, 구간이 나오면 '지지'가 되는 데이터다.
def test_h7_needs_min_units_per_group():
    def units(n_fresh):
        n = n_fresh + 40
        target = pd.DataFrame(
            {
                "athlete": [f"a{i % 10}" for i in range(n)],
                "label_ftp": [250.0] * n,
                "fresh": [True] * n_fresh + [False] * 40,
            }
        )
        pred = pd.Series([252.0] * n_fresh + [262.0] * 40)
        return target, pred

    target, pred = units(ftp.H7_MIN_UNITS - 1)
    r = ftp._h7(target, pred, n=200)
    assert r == {"diff_stale_minus_fresh": None, "verdict": "판정 불가"}
    target, pred = units(ftp.H7_MIN_UNITS)
    r = ftp._h7(target, pred, n=200)
    assert r["diff_stale_minus_fresh"]["point"] == 10.0
    assert r["verdict"] == "지지"


# ----------------------------------------------------------------------------- calibrate: R1 80% 예측 구간
# 아래 테스트는 docs/experiments.md 3단계 'R1 조건부 80% 예측 구간'(규칙 변경 18)의 정의를 고정한다. 규칙은
# 측정 전에 커밋했고, 여기 값은 모두 손으로 셀 수 있는 합성 단위로 만든다(실제 units.parquet를 읽지 않는다).


# split conformal 분위의 순서 통계량 번호: q_lo = r(⌊0.10·(n+1)⌋), q_hi = r(⌈0.90·(n+1)⌉), 1부터 센다. n =
# 19면 2번째와 18번째, n = 9면 가장 작은 값과 가장 큰 값, n = 100이면 10번째와 91번째다. n = 8이면 ⌊0.9⌋ =
# 0번째가 되어 정의되지 않으므로 None이어야 한다(규칙: 그 라이딩 수 구간은 구간을 내지 않음). 입력 순서와
# 무관해야 하므로 섞어서 넣는다. 0.1·(n+1)을 부동소수로 계산하면 경계에서 한 칸 어긋날 수 있어 정수 산술로
# 구현했는지도 n = 9·19 (0.1·(n+1)이 정확히 정수인 경우)로 확인된다.
def test_conformal_bounds_are_the_registered_order_statistics():
    rng = np.random.default_rng(0)
    r19 = rng.permutation(np.arange(1.0, 20.0))
    b = ftp.conformal_bounds(r19)
    assert (b["k_lo"], b["k_hi"], b["q_lo"], b["q_hi"]) == (2, 18, 2.0, 18.0)
    b = ftp.conformal_bounds(rng.permutation(np.arange(1.0, 10.0)))
    assert (b["k_lo"], b["k_hi"], b["q_lo"], b["q_hi"]) == (1, 9, 1.0, 9.0)
    b = ftp.conformal_bounds(rng.permutation(np.arange(1.0, 101.0)))
    assert (b["k_lo"], b["k_hi"], b["q_lo"], b["q_hi"]) == (10, 91, 10.0, 91.0)
    assert ftp.conformal_bounds(np.arange(1.0, 9.0)) is None
    assert ftp.conformal_bounds(np.array([])) is None


# 창 안 라이딩 수 구간은 3단계 집단별 보고(breakdown)와 같은 8~12, 13~20, 21~30, 31 이상이다. 경계
# 값(7·8·12·13· 20·21·30·31)을 고정한다. 8 미만은 측정 조건 밖이라 구간이 없다(None). 서버도 같은 경계를
# 쓰므로 여기서 어긋나면 서비스가 다른 보정값을 붙이게 된다.
def test_rides_bucket_boundaries():
    got = {n: ftp.rides_bucket(n) for n in (0, 7, 8, 12, 13, 20, 21, 30, 31, 500)}
    assert got == {
        0: None,
        7: None,
        8: "8-12",
        12: "8-12",
        13: "13-20",
        20: "13-20",
        21: "21-30",
        30: "21-30",
        31: "31+",
        500: "31+",
    }
    assert [b[0] for b in ftp.RIDES_BUCKETS] == ["8-12", "13-20", "21-30", "31+"]


# 보정·확인용 합성 단위. 모든 단위의 20분 최대가 200 W라 R1 = 190 W이고, 잔차 L_FTP − R1은 라벨 − 190이다.
# - train: 8~12개 구간 19단위(잔차 1~19 → q_lo 2, q_hi 18), 13~20개 구간 9단위(잔차 −40~+40, 10 W 간격 →
#   최소·최대), 21~30개 구간 8단위(n < 9라 구간 없음), 31개 이상 0단위(구간 없음), 20분 기록 없는 단위
#   1개(빠져야 함)
# - dev: 8~12개 5단위(라벨 192·208은 양 끝이라 포함, 191·209는 밖, 200은 포함 → 3/5), 13~20개 3단위(150은
#   끝이라 포함, 231은 밖, 190은 포함 → 2/3), 21~30개 2단위·31개 이상 1단위(구간이 없어 포함률에서 빠짐), 20분
#   기록 없는 단위 1개
# - test: 라벨이 엉뚱한 단위 4개. 보정·확인에 쓰이면 위 값이 모두 바뀐다
def _interval_units() -> pd.DataFrame:
    rows = []

    def add(split, athlete, n_rides, label, fresh=False, mmp_20m=200.0):
        rows.append(
            {
                "athlete": athlete,
                "split": split,
                "n_rides": n_rides,
                "mmp_20m": mmp_20m,
                "label_ftp": label,
                "fresh": fresh,
            }
        )

    for k in range(1, 20):
        add("train", f"t{k % 5}", 10, 190.0 + k)
    for i, k in enumerate(range(-40, 41, 10)):
        add("train", f"u{i % 3}", 15, 190.0 + k)
    for k in range(8):
        add("train", "v0", 25, 190.0 + 3 * k)
    add("train", "t0", 10, 500.0, mmp_20m=np.nan)
    # dev 8~12개 구간: 선수 d0 3단위, d1 2단위
    add("dev", "d0", 9, 192.0, fresh=True)
    add("dev", "d0", 9, 208.0)
    add("dev", "d0", 9, 191.0, fresh=True)
    add("dev", "d1", 9, 209.0)
    add("dev", "d1", 9, 200.0)
    # dev 13~20개 구간
    add("dev", "d1", 20, 150.0, fresh=True)
    add("dev", "d2", 20, 231.0)
    add("dev", "d2", 20, 190.0)
    # dev 구간 없는 라이딩 수(21~30, 31+)와 20분 기록 없는 단위
    add("dev", "d2", 21, 195.0, fresh=True)
    add("dev", "d2", 30, 185.0)
    add("dev", "d0", 40, 250.0)
    add("dev", "d0", 9, 400.0, mmp_20m=np.nan)
    for i in range(4):
        add("test", f"s{i}", 10 + 7 * i, 900.0 - 200 * i)
    return pd.DataFrame(rows)


# 보정 표(train)와 dev 확인 값을 손으로 센 값과 맞춘다. 구간 = [R1 + q_lo, R1 + q_hi]이고 양 끝을
# 포함한다(라벨 192·208·150이 포함). 포함률 분모는 구간이 있는 dev 단위 8개이고(5 + 3), 구간이 없는 라이딩
# 수의 dev 단위 3개는 따로 센다. 20분 기록이 없는 단위는 분할마다 빠진 수로 보고한다. 신선/묵은 라벨별 포함률,
# 폭 중앙값(16 W 5개 + 80 W 3개 → 16), 지금 안내를 대칭 구간 R1 ± (dev MAE 반올림)으로 읽은 포함률도 확인한다:
# dev 단위 11개의 |오차| 합 201 / 11 = 18.3 → 18 W, |오차| ≤ 18인 단위 7개.
def test_calibrate_uses_train_per_bucket_and_checks_dev():
    units = _interval_units()
    assert ftp.rule_r1(units.iloc[:1]).iloc[0] == 190.0  # 아래 기대값의 전제(R1 = 190 W)
    r = ftp.calibrate(units)
    assert r["level"] == 0.8
    assert r["calibrated_on"] == "train" and r["checked_on"] == "dev"
    assert r["excluded_no_20m"] == {"train": 1, "dev": 1}
    assert r["n_train"] == 36 and r["n_dev"] == 11
    b = {x["name"]: x for x in r["buckets"]}
    assert list(b) == ["8-12", "13-20", "21-30", "31+"]
    assert (b["8-12"]["n_train"], b["8-12"]["q_lo"], b["8-12"]["q_hi"], b["8-12"]["width"]) == (
        19,
        2.0,
        18.0,
        16.0,
    )
    assert (b["13-20"]["n_train"], b["13-20"]["q_lo"], b["13-20"]["q_hi"]) == (9, -40.0, 40.0)
    assert (b["8-12"]["min_rides"], b["8-12"]["max_rides"], b["31+"]["min_rides"], b["31+"]["max_rides"]) == (
        8,
        12,
        31,
        None,
    )
    for name in ("21-30", "31+"):
        assert b[name]["q_lo"] is None and b[name]["q_hi"] is None
    assert (b["21-30"]["n_train"], b["31+"]["n_train"]) == (8, 0)
    assert b["8-12"]["dev"]["coverage"]["point"] == pytest.approx(3 / 5)
    assert b["8-12"]["dev"]["n_units"] == 5 and b["8-12"]["dev"]["n_athletes"] == 2
    assert b["13-20"]["dev"]["coverage"]["point"] == pytest.approx(2 / 3)
    assert b["21-30"]["dev"]["n_units"] == 2 and b["21-30"]["dev"]["coverage"]["point"] is None
    d = r["dev"]
    assert d["n_units"] == 8 and d["n_athletes"] == 3 and d["n_without_interval"] == 3
    assert d["coverage"]["point"] == pytest.approx(5 / 8)
    assert d["coverage"]["n_athletes"] == 3
    assert d["median_width"] == 16.0
    assert d["fresh"]["point"] == pytest.approx(2 / 3)
    assert d["stale"]["point"] == pytest.approx(3 / 5)
    assert d["symmetric_dev_mae"]["half_width_watts"] == 18
    assert d["symmetric_dev_mae"]["coverage"]["point"] == pytest.approx(7 / 11)
    assert r["H14"]["coverage"] == d["coverage"]
    assert r["H14"]["verdict"] == ftp._verdict_h14(d["coverage"])


# 봉인 test 단위는 읽어도 쓰지 않는다(규칙: test는 3단계에서 한 번 썼다). test 단위를 빼거나 라벨·라이딩 수를
# 바꿔도 결과가 바이트 단위로 같아야 한다. 결과에 test 단위 수 같은 흔적도 남기지 않는다.
def test_calibrate_never_uses_test_units():
    units = _interval_units()
    base = json.dumps(ftp.calibrate(units), sort_keys=True, default=str)
    no_test = units[units["split"] != "test"]
    changed = units.copy()
    is_test = changed["split"] == "test"
    changed.loc[is_test, "label_ftp"] = 191.0
    changed.loc[is_test, "n_rides"] = 9
    assert json.dumps(ftp.calibrate(no_test), sort_keys=True, default=str) == base
    assert json.dumps(ftp.calibrate(changed), sort_keys=True, default=str) == base
    assert '"test"' not in base


# H14 판정 경계(dev 참고 판정): 선수 단위 95% 구간이 [76%, 84%] 안이고 80%를 포함하면 '보정 유지', 상한 < 76%
# 또는 하한 > 84%면 '보정 실패', 그 밖(경계를 걸침, 76~84% 안이지만 80%를 포함하지 않음, 구간 없음)은 '판정
# 불가'.
def test_h14_verdict_boundaries():
    def v(lo, hi):
        return ftp._verdict_h14({"ci": [lo, hi]})

    assert v(0.77, 0.83) == "보정 유지"
    assert v(0.76, 0.84) == "보정 유지"
    assert v(0.80, 0.80) == "보정 유지"
    assert v(0.70, 0.7599) == "보정 실패"
    assert v(0.8401, 0.90) == "보정 실패"
    assert v(0.70, 0.76) == "판정 불가"
    assert v(0.84, 0.90) == "판정 불가"
    assert v(0.74, 0.82) == "판정 불가"
    assert v(0.78, 0.86) == "판정 불가"
    assert v(0.81, 0.83) == "판정 불가"
    assert v(0.77, 0.79) == "판정 불가"
    assert v(None, None) == "판정 불가"


# 명령행: calibrate는 --units와 --out만 받아 <out>.json·<out>.md를 쓴다. JSON은 calibrate()의 결과와 같고 줄
# 끝은 LF다. 분할을 고르는 옵션(--split)은 없어야 한다(봉인 test로 구간을 재는 길을 도구 수준에서 막는다).
def test_calibrate_cli_writes_reports_and_has_no_split_option(tmp_path: Path):
    units = _interval_units()
    p = tmp_path / "units.parquet"
    units.to_parquet(p, index=False)
    out = tmp_path / "r1-intervals"
    ftp.main(["calibrate", "--units", str(p), "--out", str(out)])
    raw = Path(f"{out}.json").read_bytes()
    assert b"\r\n" not in raw
    expected = json.loads(json.dumps(ftp.calibrate(pd.read_parquet(p)), ensure_ascii=False, default=str))
    assert json.loads(raw.decode("utf-8")) == expected
    md = Path(f"{out}.md").read_text(encoding="utf-8")
    assert "H14" in md and "8-12" in md and "개발 세트" in md
    with pytest.raises(SystemExit):
        ftp.main(["calibrate", "--units", str(p), "--out", str(out), "--split", "test"])
