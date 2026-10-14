"""3단계: 최근 6주 라이딩으로 FTP를 추정한다(docs/experiments.md, 3단계).

하위 명령:

* ``build``     창 특징과 라벨을 붙인 예측 단위(선수, 기준일)를 만든다
* ``evaluate``  후보를 train으로 맞추고, 한 분할에서 MAE 등을 선수 단위 부트스트랩
                구간과 함께 보고한다

입력: Kotlin ``parity`` 태스크가 표본 선수마다 쓴 라이딩별 JSONL(라이딩마다 우리 구현의 NP와
최대 평균 파워 16구간, 그리고 GoldenCheetah의 cp_setting / NP / IF), 라이딩 인덱스 폴더(성별,
출생 연도), 표본 매니페스트(선수별 분할).
"""

# [파일 역할] 3단계 FTP 추정 실험의 본체다. 라이딩별 특징(JSONL)을 '예측 단위'(선수, 기준일)로 묶어
# 특징·라벨을 만들고(build), 후보 7개(규칙 R1·R2·R3·R5, 모델 M1·M1b·M2)를 train으로 맞춘 뒤 dev 또는 test에서
# 선수 단위 부트스트랩으로 MAE 등을 잰다(evaluate).
# [왜 이렇게 나눴나] 측정 규칙(docs/experiments.md 3단계)이 '후보와 판정 기준을 먼저 적고, dev로 채택 후보를
# 정한 뒤, 봉인 test는 한 번만 잰다'이므로, 단위 만들기와 평가를 분리해 같은 units.parquet로 dev와 test를 따로
# 돌린다.
# [입력] (1) Kotlin parity 태스크(ParityTool.kt)가 쓴 라이딩별 JSONL: 우리 구현의 NP와 MMP 16구간,
# GoldenCheetah 메타데이터의 cp_setting·NP·IF. (2) rides.py가 만든 선수별 인덱스 JSON(성별, 출생 연도). (3)
# 표본 매니페스트(선수별 split).
# [출력] build: units.parquet와 요약 JSON. evaluate: <out>.json, <out>.md(reports/ftp/dev.*, test.*).
# [결과 요약] 봉인 test에서 채택 후보 M2(Ridge)는 R1보다 MAE 6.3 W 낮았지만 '5 W 이상' 기준을 구간으로
# 확정하지 못해(H5 판정 불가) 서비스는 규칙 R1을 쓴다(docs/experiments.md 3단계 결과).

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path

# numpy: 부트스트랩 재표집(seed를 받은 Generator), CP 직선 적합(polyfit), 결측(NaN) 처리.
# pandas: 라이딩 표를 선수별로 묶고 날짜 창으로 거르는 데 쓴다. 예측 단위는 parquet으로 저장한다.
# lightgbm·scikit-learn은 모델 후보의 fit() 안에서만 불러온다(아래 참고).
import numpy as np
import pandas as pd

# 예측 단위 규칙의 상수(docs/experiments.md 3단계 '데이터'에 미리 적은 값).
# - WINDOW_DAYS: 특징을 모으는 창. 기준일을 포함해 직전 42일(6주)이다. '최근 6주로 FTP를 맞힌다'는 질문 정의
#   그대로다.
# - STEP_DAYS: 기준일 간격 28일. 창(42일)보다 짧아 같은 선수의 이웃 단위끼리 창이 일부 겹친다.
# - MIN_RIDES: 창 안 파워 라이딩이 8개 미만인 단위는 뺀다.
# - FRESH_DAYS: 기준일 전후 28일 안에 라벨이 바뀌면 '신선 라벨'로 본다(H7).
# - LABEL_MIN/MAX: 50 W 미만, 600 W 초과 라벨은 입력 오류로 보고 뺀다.
WINDOW_DAYS = 42
STEP_DAYS = 28
MIN_RIDES = 8
FRESH_DAYS = 28
LABEL_MIN, LABEL_MAX = 50, 600
# MMP_KEYS: GoldenCheetah 메트릭 이름 '<구간>_critical_power'의 구간 부분. 1초~90분 16구간 최대 평균
# 파워(MMP)이며 1단계 파리티 검증과 같은 구간이다.
MMP_KEYS = [
    "1s",
    "5s",
    "10s",
    "15s",
    "20s",
    "30s",
    "1m",
    "2m",
    "3m",
    "5m",
    "8m",
    "10m",
    "20m",
    "30m",
    "60m",
    "90m",
]
# CP_FIT_KEYS: R3(Critical Power 2변수 모델) 적합에 쓰는 구간과 그 길이(초). 규칙에 적힌 3·5·8·10·20분이다.
CP_FIT_KEYS = {"3m": 180, "5m": 300, "8m": 480, "10m": 600, "20m": 1200}
# 부트스트랩 2,000회, seed 0은 공통 규칙(docs/experiments.md '공통')이다. 프로젝트 규칙상 무작위는 seed를 받은
# numpy Generator로만 만든다.
BOOTSTRAP_N = 2000
SEED = 0
# H7_MIN_UNITS: H7(신선/묵은 라벨 MAE 차이)에서 한 집단의 단위가 이보다 적으면 구간을 내지 않고 판정 불가로
# 둔다. 처음부터 코드에 있던 하한인데 등록한 규칙에는 빠져 있어 측정 뒤 규칙에 적었다(docs/experiments.md 규칙
# 변경 10). 봉인 test는 신선 315·묵은 1,271단위라 이 하한에 걸리지 않았다.
H7_MIN_UNITS = 20
# LGBM_PARAMS: M1·M1b LightGBM의 하이퍼파라미터. 측정 전에 규칙에 고정해 두었고 튜닝하지 않는다(미리 정해 두면
# dev를 보며 값을 고르는 과정에서 생기는 낙관적 치우침이 없다). subsample_freq=1은 subsample(행 표집)이 실제로
# 켜지게 하는 LightGBM 설정이다(0이면 행 표집을 하지 않는다). ftp_export.py도 같은 값을 가져다 써서 Kotlin
# 대조용 모델을 만든다.
LGBM_PARAMS = dict(
    num_leaves=15,
    learning_rate=0.05,
    n_estimators=400,
    min_child_samples=50,
    subsample=0.8,
    subsample_freq=1,
    colsample_bytree=0.8,
    random_state=SEED,
    verbose=-1,
)


# ----------------------------------------------------------------------------- build: 예측 단위 만들기


# JSONL 한 줄(라이딩 하나)을 평평한 dict로 바꿔 하나씩 내보내는 생성기. load_rides()가 이것으로 DataFrame을
# 만든다.
# 뽑는 것: 선수, 시작 시각(UTC), 우리 NP, 기록 시간(시간), GC가 IF에 실제로 쓴 임계값(thr), cp_setting(cp),
# 체중, MMP 16구간.
def _ride_rows(jsonl: Path):
    for line in jsonl.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        gc = d["gc"]
        np_gc, if_gc = gc.get("np"), gc.get("if")
        # thr: GC가 IF 계산에 쓴 임계값을 NP / IF로 역산해 정수로 반올림한다. 1단계에서 GC가 IF·TSS에
        # cp_setting이 아닌 존 설정의 FTP를 쓴 라이딩이 10~13% 있었기 때문에(규칙 변경 2), 사용자가 'FTP'로 둔
        # 값에 더 가까운 이 값을 주 라벨 L_FTP로 쓴다. NP나 IF가 없거나 0이면(GC의 '값 없음') None.
        thr = round(np_gc / if_gc) if np_gc and if_gc and if_gc > 0 else None
        row = {
            "athlete": d["athlete"],
            # GC 메타데이터의 날짜 형식 '2020/01/31 07:00:00 UTC'에서 ' UTC'를 떼고 읽는다(시간대 없는 UTC
            # 시각).
            "date": dt.datetime.strptime(d["date"].replace(" UTC", ""), "%Y/%m/%d %H:%M:%S"),
            "np": d["ours"]["np"],
            # 기록 시간 = 샘플 수 × 기록 간격(초) / 3600. 간격을 추정하지 못한 라이딩(rec_int가 null)은
            # 0시간으로 센다.
            "hours": d["samples"] * (d["rec_int"] or 0) / 3600.0,
            "thr": thr,
            "cp": d.get("cp"),
            "weight": d.get("weight"),
        }
        # MMP는 GC 값이 아니라 우리 Kotlin 구현이 계산한 값(ours)을 쓴다(docs/experiments.md 3단계 데이터
        # 규칙).
        for k in MMP_KEYS:
            row[f"mmp_{k}"] = d["ours"]["mmp"].get(f"{k}_critical_power")
        yield row


# JSONL 전체를 라이딩 DataFrame으로 읽고 선수·날짜 순으로 정렬한다. 호출 위치: cmd_build, tests/test_ftp.py.
# 정렬해 두는 이유: build_units가 창의 마지막 라이딩(iloc[-1])을 '가장 최근 라이딩'으로 쓰므로 시간 순서가
# 보장돼야 한다.
def load_rides(jsonl: Path) -> pd.DataFrame:
    df = pd.DataFrame(list(_ride_rows(jsonl)))
    # MMP 열을 숫자로 바꾸고(이상한 값은 NaN), 0 이하는 NaN으로 둔다. Kotlin 구현은 라이딩이 구간보다 짧으면
    # null을, 평균이 3000 W 이상이면 GC처럼 0을 내므로 둘 다 '값 없음'으로 통일하는 것이다. 0을 그대로 두면 창
    # 안 라이딩이 모두 0일 때 최대값이 NaN이 아니라 0이 되어 R1 = 0 같은 엉뚱한 예측이 평가에 들어간다.
    for k in MMP_KEYS:
        col = f"mmp_{k}"
        df[col] = pd.to_numeric(df[col], errors="coerce")
        df.loc[df[col] <= 0, col] = np.nan  # GoldenCheetah의 '값 없음'(0)을 NaN으로
    return df.sort_values(["athlete", "date"]).reset_index(drop=True)


# 한 선수의 라이딩 날짜(정렬된 Series)에서 기준일 목록을 뽑는다. 첫 라이딩 날짜를 첫 기준일로 두고, 직전
# 기준일 + 28일 이후의 첫 라이딩 날짜를 다음 기준일로 고른다. 기준일을 실제 라이딩 날짜로 잡아 창의 마지막
# 날에 항상 라이딩(=라벨을 읽을 라이딩)이 있게 한다. 호출 위치: build_units, tests/test_ftp.py.
def reference_dates(dates: pd.Series) -> list[pd.Timestamp]:
    """첫 라이딩 날짜, 그다음은 직전 기준일에서 STEP_DAYS 이상 지난 첫 라이딩 날짜를 기준일로 고른다."""
    refs: list[pd.Timestamp] = []
    nxt = None
    for d in dates:
        if nxt is None or d >= nxt:
            refs.append(d)
            nxt = d + pd.Timedelta(days=STEP_DAYS)
    return refs


# 라이딩 표를 예측 단위 표로 바꾼다. 한 행 = (선수, 기준일) 하나와 그 창의 특징·라벨·신선도.
# 입력: rides(load_rides 결과), athletes(선수 id → 성별·출생 연도), splits(선수 id → train/dev/test).
# 출력: 단위 DataFrame. 호출 위치: cmd_build, tests/test_ftp.py.
# 분할을 선수 단위 매니페스트에서 그대로 가져오므로 같은 선수의 단위가 여러 분할에 섞이지 않는다(처음 보는
# 선수에게 일반화되는지 재려는 설계).
def build_units(rides: pd.DataFrame, athletes: dict[str, dict], splits: dict[str, str]) -> pd.DataFrame:
    units = []
    for athlete, g in rides.groupby("athlete", sort=False):
        g = g.sort_values("date")
        meta = athletes.get(athlete, {})
        for ref in reference_dates(g["date"]):
            # 창 = (기준일 − 42일, 기준일]. 기준일 이후 라이딩은 넣지 않아 특징에 미래 정보가 들어가지 않는다.
            w = g[(g["date"] > ref - pd.Timedelta(days=WINDOW_DAYS)) & (g["date"] <= ref)]
            if len(w) < MIN_RIDES:
                continue
            # 라벨은 창의 마지막 파워 라이딩에서 읽는다. 주 라벨 L_FTP = GC가 IF에 쓴 임계값(thr), 없으면
            # cp_setting, 둘 다 없으면 단위를 뺀다(규칙의 L_FTP 정의).
            last = w.iloc[-1]
            label = last["thr"] if pd.notna(last["thr"]) else (last["cp"] if pd.notna(last["cp"]) else None)
            if label is None or not (LABEL_MIN <= label <= LABEL_MAX):
                continue
            # 신선도(H7용): 기준일 ±FRESH_DAYS(28일) 안에 선수의 FTP 라벨이 바뀌었으면, 즉 그 구간의 라이딩
            # 중 라벨이 지금 라벨과 다른 것이 하나라도 있으면 '최근에 FTP를 갱신한 선수'로 보고 fresh=True
            # (등록한 규칙 그대로). 이 판정에는 기준일 뒤 라이딩도 쓰지만, 특징이 아니라 결과를 나눠 보는 집단
            # 구분에만 쓰므로 예측에 정보가 새지 않는다. 처음 구현은 등록한 정의와 달리 −28일 시점의 값과
            # +28일 뒤의 값을 비교했고, 2026-10-10 Codex 3차 검토 뒤 고쳤다(규칙 변경 5).
            near = g[
                (g["date"] >= ref - pd.Timedelta(days=FRESH_DAYS))
                & (g["date"] <= ref + pd.Timedelta(days=FRESH_DAYS))
            ]
            near_labels = near["thr"].where(near["thr"].notna(), near["cp"]).dropna()
            fresh = bool((near_labels != label).any())
            # 체중은 창 안 라이딩 중 값이 있는 마지막 것을 쓴다(규칙: 그 라이딩의 athlete_weight 중 마지막
            # 값). ParityTool이 20~300 kg 밖의 값은 이미 null로 바꿔 둔다.
            weights = w["weight"].dropna()
            yob = meta.get("yob")
            # 단위 한 행의 열.
            # - label_ftp(주 라벨), label_cp(보조 라벨: 같은 라이딩의 cp_setting, 없으면 NaN), fresh(신선 라벨
            #   여부)
            # - n_rides·hours·np_mean·np_max·days_since_last: 창 안 훈련량과 최근성
            # - gender_m: 남 1, 여 0, 모름 NaN. age: 기준일 연도 − 출생 연도(숫자일 때만). weight: 위에서 구한
            #   체중
            # - 아래 반복문의 mmp_<구간>: 창 안 라이딩들의 구간별 최대값(값이 하나도 없으면 NaN)
            unit = {
                "athlete": athlete,
                "split": splits.get(athlete, "unknown"),
                "ref_date": ref.date().isoformat(),
                "label_ftp": float(label),
                "label_cp": float(last["cp"]) if pd.notna(last["cp"]) else np.nan,
                "fresh": bool(fresh),
                "n_rides": int(len(w)),
                "hours": float(w["hours"].sum()),
                "np_mean": float(w["np"].mean()),
                "np_max": float(w["np"].max()),
                "days_since_last": float((ref - w["date"].iloc[-1]).days),
                "gender_m": 1.0
                if meta.get("gender") == "M"
                else (0.0 if meta.get("gender") == "F" else np.nan),
                "age": float(ref.year - int(yob)) if yob and str(yob).isdigit() else np.nan,
                "weight": float(weights.iloc[-1]) if not weights.empty else np.nan,
            }
            for k in MMP_KEYS:
                unit[f"mmp_{k}"] = float(w[f"mmp_{k}"].max()) if w[f"mmp_{k}"].notna().any() else np.nan
            units.append(unit)
    return pd.DataFrame(units)


# build 하위 명령. 라이딩 JSONL → 단위 parquet. 호출 위치: main, tests/test_ftp.py.
# 선수 메타데이터(성별·출생 연도)는 rides.py 인덱스의 <선수>.json에서, 분할은 표본 매니페스트에서 읽는다. 결과
# 옆에 <out>.summary.json으로 라이딩·선수·단위 수, 분할별 단위 수, 신선 단위 수, 20분 MMP·체중이 있는 단위
# 수를 남긴다(reports/ftp/build.json의 근거 수치).
def cmd_build(features: Path, index_dir: Path, sample: Path, out: Path) -> None:
    rides = load_rides(features)
    splits = {r["id"]: r["split"] for r in json.loads(sample.read_text(encoding="utf-8"))}
    athletes = {}
    for aid in rides["athlete"].unique():
        f = index_dir / f"{aid}.json"
        if f.exists():
            d = json.loads(f.read_text(encoding="utf-8"))
            athletes[aid] = {"gender": d.get("gender"), "yob": d.get("yob")}
    units = build_units(rides, athletes, splits)
    out.parent.mkdir(parents=True, exist_ok=True)
    units.to_parquet(out, index=False)
    summary = {
        "rides": int(len(rides)),
        "athletes": int(rides["athlete"].nunique()),
        "units": int(len(units)),
        "by_split": {k: int(v) for k, v in units["split"].value_counts().items()},
        "fresh": int(units["fresh"].sum()),
        "with_20m": int(units["mmp_20m"].notna().sum()),
        "with_weight": int(units["weight"].notna().sum()),
    }
    out.with_suffix(".summary.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print(
        f"rides={len(rides)} athletes={rides['athlete'].nunique()} units={len(units)} "
        f"by_split={units['split'].value_counts().to_dict()} fresh={int(units['fresh'].sum())} "
        f"with_20m={int(units['mmp_20m'].notna().sum())} with_weight={int(units['weight'].notna().sum())}"
    )


# ----------------------------------------------------------------------------- 후보 방법

# FEATURES: 모델 M1·M2가 쓰는 기본 특징 23개(MMP 16 + 훈련량·최근성 5 + 성별·나이 2). 체중은 들어 있지 않다.
# 규칙에는 체중을 기본 특징으로 적었지만 구현은 M1b에만 넣었고, 측정 뒤에 발견해 다시 돌리지 않고 기록만
# 했다(규칙 변경 6).
FEATURES = [f"mmp_{k}" for k in MMP_KEYS] + [
    "n_rides",
    "hours",
    "np_mean",
    "np_max",
    "days_since_last",
    "gender_m",
    "age",
]


# R1: 창 안 20분 최대 × 0.95. 흔히 쓰는 FTP 추정 규칙이고, 서버(FtpRules.twentyMinute, FtpEstimateService)가
# 실제로 서비스하는 방법이다.
def rule_r1(u: pd.DataFrame) -> pd.Series:
    return 0.95 * u["mmp_20m"]


# R2: 창 안 60분 최대 그대로(FTP의 정의 '1시간 유지 가능 파워'에 가장 가까운 직접 측정). 60분 이상 라이딩이
# 없으면 NaN이라 적용 비율(coverage)이 낮아진다.
def rule_r2(u: pd.DataFrame) -> pd.Series:
    return u["mmp_60m"]


# R3: CP 2변수 모델. 구간 t초 동안의 일(W×초) = MMP × t가 CP × t + W'라는 직선 위에 있다고 보고, 3·5·8·10·20분
# 다섯 점에 최소제곱 직선을 맞춰 기울기를 CP로 쓴다(절편이 W'). 선형 회귀라 자유 매개변수 없이 닫힌 해가
# 나온다.
def rule_r3(u: pd.DataFrame) -> pd.Series:
    """3~20분 최대 평균 파워에 2변수 모델 work = CP × t + W'를 맞춰 임계 파워(CP)를 구한다."""
    out = np.full(len(u), np.nan)
    t = np.array(list(CP_FIT_KEYS.values()), dtype=float)
    p = u[[f"mmp_{k}" for k in CP_FIT_KEYS]].to_numpy(dtype=float)
    for i in range(len(u)):
        # 다섯 점 중 값이 있는 점이 3개 미만이면 직선이 불안정해 추정하지 않는다(NaN).
        ok = np.isfinite(p[i])
        if ok.sum() < 3:
            continue
        work = p[i][ok] * t[ok]
        slope, _ = np.polyfit(t[ok], work, 1)
        out[i] = slope
    return pd.Series(out, index=u.index)


# 후보 방법의 공통 틀. evaluate()가 모든 후보를 같은 방식(fit(train) → predict(target))으로 다루게 하려는 기반
# 클래스다.
# - needs_weight: 체중이 있는 단위로만 학습·평가하는 후보(M1b)
# - min_train: 학습에 필요한 최소 train 단위 수. 모델은 50, 규칙은 0. 규칙에도 50을 적용했다가 작은 합성
#   데이터에서 R1이 빠져 KeyError가 난 실수를 고친 흔적이다(docs/ai-verification.md 3단계).
class Candidate:
    """규칙(학습 없음) 또는 모델(train으로 학습)을 나타낸다. 규칙은 ``min_train``이 0이다."""

    def __init__(self, name: str, needs_weight: bool = False, min_train: int = 50):
        self.name = name
        self.needs_weight = needs_weight
        self.min_train = min_train

    # 규칙은 학습할 것이 없으므로 기본 fit은 아무것도 하지 않는다.
    def fit(self, train: pd.DataFrame) -> None:  # noqa: B027 - 규칙은 학습할 것이 없다
        pass

    def predict(self, u: pd.DataFrame) -> pd.Series:
        raise NotImplementedError


# 학습이 없는 규칙(R1·R2·R3)을 Candidate로 감싼다. fn(units) → 예측 Series.
class RuleCandidate(Candidate):
    def __init__(self, name, fn):
        super().__init__(name, min_train=0)
        self.fn = fn

    def predict(self, u):
        return self.fn(u)


# R5: R1을 train에서 선형 보정한 것(a × R1 + b). R1의 체계적 치우침(편향)만 고쳐도 모델만큼 좋아지는지 보려는
# 후보다. 적합 전 기본값은 a=1, b=0(R1과 같음).
class CalibratedR1(Candidate):
    def __init__(self):
        super().__init__("R5")
        self.a, self.b = 1.0, 0.0

    def fit(self, train):
        # 20분 MMP가 있는 train 단위에서 라벨 = a × R1 + b를 1차 다항식 최소제곱(np.polyfit)으로 푼다.
        x = rule_r1(train)
        ok = x.notna()
        self.a, self.b = np.polyfit(x[ok], train.loc[ok, "label_ftp"], 1)

    def predict(self, u):
        return self.a * rule_r1(u) + self.b


# 모델 입력 행렬을 만든다. 기본은 FEATURES 23열. with_weight=True(M1b)이면 체중당 파워(W/kg) 5분·20분·60분과
# 체중을 더한다. 호출 위치: LgbmCandidate, RidgeCandidate._x, ftp_export.py(Kotlin 대조용 모델이 같은 열
# 순서를 쓰도록).
def _matrix(u: pd.DataFrame, with_weight: bool) -> pd.DataFrame:
    x = u[FEATURES].copy()
    if with_weight:
        for k in ("5m", "20m", "60m"):
            x[f"wkg_{k}"] = u[f"mmp_{k}"] / u["weight"]
        x["weight"] = u["weight"]
    return x


# M1(기본 특징)·M1b(+ 체중당 특징) LightGBM 회귀. LightGBM은 결측값(NaN)을 트리 분기에서 직접 다루므로 따로
# 채우지 않는다. 서비스에 넣게 되면 dump_model() JSON을 Kotlin에서 직접 순회해 추론하도록 준비해
# 두었다(LightGbmModel.kt, ftp_export.py).
class LgbmCandidate(Candidate):
    def __init__(self, name="M1", with_weight=False):
        super().__init__(name, needs_weight=with_weight)
        self.with_weight = with_weight
        self.model = None

    def fit(self, train):
        # 모듈 맨 위가 아니라 여기서 불러와 build만 돌리거나 규칙만 평가할 때는 무거운 라이브러리를 읽지 않게
        # 한다.
        import lightgbm as lgb

        self.model = lgb.LGBMRegressor(**LGBM_PARAMS)
        self.model.fit(_matrix(train, self.with_weight), train["label_ftp"])

    def predict(self, u):
        return pd.Series(self.model.predict(_matrix(u, self.with_weight)), index=u.index)


# M2: Ridge 회귀(α = 1). 선형 모델이라 서비스에 넣을 때 계수만 옮기면 된다. dev MAE가 가장 낮아(21.458 W, M1과
# 0.008 W 차) 규칙대로 채택 후보가 됐다(docs/experiments.md 3단계 결과).
class RidgeCandidate(Candidate):
    def __init__(self):
        super().__init__("M2")
        self.pipe = None
        self.medians = None

    # MMP에 로그를 씌운다(1 W 미만은 1로 잘라 log(0) 방지). 규칙에 적힌 전처리('log(MMP)와 나머지 특징
    # 표준화')다. 로그를 씌우면 파워의 배율 차이가 덧셈 차이가 되어 선형 모델에 넣기 알맞다.
    def _x(self, u):
        x = _matrix(u, False)
        for k in MMP_KEYS:
            x[f"mmp_{k}"] = np.log(x[f"mmp_{k}"].clip(lower=1))
        return x

    def fit(self, train):
        from sklearn.linear_model import Ridge
        from sklearn.pipeline import make_pipeline
        from sklearn.preprocessing import StandardScaler

        # Ridge는 NaN을 받지 못하므로 train의 열별 중앙값으로 결측을 채운다. 중앙값은 train에서만 구해 predict
        # 때도 같은 값을 쓴다(평가 데이터 정보가 학습에 새지 않게). StandardScaler로 표준화한 뒤 Ridge를 거는
        # 파이프라인이라 α의 벌점이 열의 단위에 좌우되지 않는다.
        x = self._x(train)
        self.medians = x.median()
        self.pipe = make_pipeline(StandardScaler(), Ridge(alpha=1.0))
        self.pipe.fit(x.fillna(self.medians), train["label_ftp"])

    def predict(self, u):
        return pd.Series(self.pipe.predict(self._x(u).fillna(self.medians)), index=u.index)


# 규칙에 미리 적은 후보 7개를 새 객체로 만들어 돌려준다(R4는 돌리지 않기로 측정 전에 정함). 순서는 보고서 표의
# 행 순서가 된다.
def candidates() -> list[Candidate]:
    return [
        RuleCandidate("R1", rule_r1),
        RuleCandidate("R2", rule_r2),
        RuleCandidate("R3", rule_r3),
        CalibratedR1(),
        LgbmCandidate("M1"),
        LgbmCandidate("M1b", with_weight=True),
        RidgeCandidate(),
    ]


# ----------------------------------------------------------------------------- 평가


# 선수 단위 부트스트랩. 선수를 복원 추출로 다시 뽑고, 뽑힌 선수들의 오차를 모두 이어 붙여 stat(평균·중앙값
# 등)을 계산하기를 n번 반복해 2.5%·97.5% 분위수를 95% 구간으로 쓴다.
# 왜 선수 단위인가: 같은 선수의 단위들은 서로 닮아 독립이 아니다. 단위를 하나씩 뽑으면 구간이 실제보다 좁게
# 나온다. 선수를 통째로 뽑아야 '처음 보는 선수' 기준의 불확실성이 된다(테스트는 선수가 2명이면 구간이 양 끝에
# 닿는지 확인한다).
# 출력: point(전체 데이터의 값), ci, n_units, n_athletes. 선수가 없으면 None을 채운 dict.
def athlete_bootstrap(errors: pd.Series, athletes: pd.Series, stat, n=BOOTSTRAP_N, seed=SEED) -> dict:
    """선수를 복원 추출로 다시 뽑고, 뽑힌 선수들의 오차를 합쳐 ``stat``을 계산한다."""
    groups = {a: e.to_numpy() for a, e in errors.groupby(athletes)}
    keys = list(groups)
    if not keys:
        return {"point": None, "ci": [None, None], "n_units": 0, "n_athletes": 0}
    rng = np.random.default_rng(seed)
    vals = []
    for _ in range(n):
        # 선수 수만큼 선수 인덱스를 복원 추출한다. 선수마다 단위 수가 달라 매번 표본 크기가 달라질 수 있다.
        pick = rng.integers(0, len(keys), len(keys))
        vals.append(stat(np.concatenate([groups[keys[i]] for i in pick])))
    return {
        "point": float(stat(errors.to_numpy())),
        "ci": [float(np.quantile(vals, 0.025)), float(np.quantile(vals, 0.975))],
        "n_units": int(len(errors)),
        "n_athletes": len(keys),
    }


# 한 후보의 예측을 label 열(기본 L_FTP)과 비교해 지표 네 가지를 선수 부트스트랩 구간과 함께 낸다: MAE, 중앙값
# 절대 오차, MAPE(오차 / 라벨), 편향(예측 − 라벨의 평균, 음수면 과소 추정). coverage는 예측과 라벨이 모두 있는
# 단위 비율(R2처럼 60분 기록이 없으면 빠지는 후보의 적용 비율).
def summarize(pred: pd.Series, u: pd.DataFrame, label: str = "label_ftp") -> dict:
    ok = pred.notna() & u[label].notna()
    err = pred[ok] - u.loc[ok, label]
    ath = u.loc[ok, "athlete"]
    return {
        "coverage": float(ok.mean()) if len(ok) else 0.0,
        "mae": athlete_bootstrap(err.abs(), ath, np.mean),
        "median_ae": athlete_bootstrap(err.abs(), ath, np.median),
        "mape": athlete_bootstrap((err.abs() / u.loc[ok, label]), ath, np.mean),
        "bias": athlete_bootstrap(err, ath, np.mean),
    }


# 두 후보의 MAE 차이(a − b)를 같은 단위끼리 짝지어 잰다. 두 후보가 모두 예측한 단위에서 단위마다 |a 오차| − |b
# 오차|를 구한 뒤 선수 단위로 재표집해 평균을 낸다. 짝을 지으면 두 방법에 공통인 '어려운 선수' 효과가 상쇄돼
# 차이를 더 정밀하게 잴 수 있다(규칙: 두 방법 비교는 짝지은 부트스트랩). H5(채택 후보 − R1)에 쓴다.
def paired_mae_difference(a: pd.Series, b: pd.Series, u: pd.DataFrame, n=BOOTSTRAP_N, seed=SEED) -> dict:
    """둘 다 예측한 단위에서 MAE(a) − MAE(b)를 선수 단위로 짝지은 부트스트랩으로 잰다."""
    ok = a.notna() & b.notna()
    d = (a[ok] - u.loc[ok, "label_ftp"]).abs() - (b[ok] - u.loc[ok, "label_ftp"]).abs()
    return athlete_bootstrap(d, u.loc[ok, "athlete"], np.mean, n, seed)


# evaluate 하위 명령의 본체. 모든 후보를 train에서 맞추고 split(dev/test) 단위에서 잰 결과 dict를 돌려준다.
# adopt(dev에서 고른 채택 후보)가 주어지면 H5(채택 후보 − R1 ≤ −5 W), H7(신선/묵은 라벨 차이), 집단별 MAE를
# 더하고, R1이 있으면 항상 H6(R1의 편향 > 0)을 판정한다. 호출 위치: main, tests/test_ftp.py.
def evaluate(units: pd.DataFrame, split: str, adopt: str | None) -> dict:
    train = units[units["split"] == "train"]
    target = units[units["split"] == split]
    # 20분 MMP가 없는 단위는 R1·R3가 정의되지 않으므로 모든 방법에서 뺀다(규칙). 같은 단위 집합에서 비교해야
    # 짝지은 차이가 공정하다.
    train = train[train["mmp_20m"].notna()]
    target = target[target["mmp_20m"].notna()]
    results = {
        "split": split,
        "n_train": int(len(train)),
        "n_units": int(len(target)),
        "n_athletes": int(target["athlete"].nunique()),
        "methods": {},
    }
    preds: dict[str, pd.Series] = {}
    for c in candidates():
        # M1b는 체중이 있는 단위로만 학습·평가한다. 그래서 M1b의 수치는 다른 후보와 단위 집합이 다르다.
        tr = train[train["weight"].notna()] if c.needs_weight else train
        tg = target[target["weight"].notna()] if c.needs_weight else target
        if len(tr) < c.min_train or len(tg) == 0:
            results["methods"][c.name] = {"skipped": f"train={len(tr)} target={len(tg)}"}
            continue
        c.fit(tr)
        # 예측을 전체 target 인덱스로 다시 맞춰, M1b처럼 일부 단위만 예측한 후보는 나머지가 NaN이 되게
        # 한다(summarize의 coverage와 짝지은 비교가 이것을 이용한다).
        p = c.predict(tg).reindex(target.index)
        preds[c.name] = p
        r = summarize(p, target)
        # 보고용(판정 없음): 보조 라벨 L_CP 기준 MAE, 신선 라벨·묵은 라벨 집단별 MAE.
        r["mae_vs_label_cp"] = summarize(p, target, "label_cp")["mae"]
        r["mae_fresh"] = summarize(p[target["fresh"]], target[target["fresh"]])["mae"]
        r["mae_stale"] = summarize(p[~target["fresh"]], target[~target["fresh"]])["mae"]
        results["methods"][c.name] = r
    # R1이 기준선이므로 다른 모든 후보에 대해 'MAE − R1' 짝지은 차이를 붙인다.
    if "R1" in preds:
        for name, p in preds.items():
            if name != "R1":
                results["methods"][name]["paired_mae_minus_r1"] = paired_mae_difference(
                    p, preds["R1"], target
                )
    # 보고 항목: 신선 단위 수, L_CP가 L_FTP와 1 W 넘게 다른 단위 수, L_CP가 없는 단위 수.
    results["fresh_units"] = int(target["fresh"].sum())
    results["label_cp_differs"] = int(((target["label_cp"] - target["label_ftp"]).abs() > 1).sum())
    results["label_cp_missing"] = int(target["label_cp"].isna().sum())
    if adopt and adopt in preds:
        diff = results["methods"][adopt].get("paired_mae_minus_r1")
        # H5: 차이(채택 − R1)의 구간 상한이 −5 W 이하면 '5 W 이상 낮다'가 확정(지지), 하한이 −5 W보다 크면
        # 기각, 걸치면 판정 불가.
        results["H5"] = {"adopted": adopt, "diff": diff, "verdict": _verdict_le(diff, -5.0)}
        fresh = results["methods"][adopt]["mae_fresh"]["point"]
        stale = results["methods"][adopt]["mae_stale"]["point"]
        results["H7"] = {
            "adopted": adopt,
            "mae_fresh": fresh,
            "mae_stale": stale,
            **_h7(target, preds[adopt]),
        }
        results["breakdown"] = {
            name: breakdown(preds[name], target) for name in ("R1", adopt) if name in preds
        }
    if "R1" in preds:
        # H6: R1 편향 구간의 하한이 0보다 크면 과대 추정(지지), 상한이 0 이하면 기각. 봉인 test에서는 −17.2 W
        # [−22.9, −11.3]로 오히려 과소 추정이라 기각됐다.
        bias = results["methods"]["R1"]["bias"]
        results["H6"] = {
            "bias": bias,
            "verdict": "지지"
            if bias["ci"][0] is not None and bias["ci"][0] > 0
            else ("기각" if bias["ci"][1] is not None and bias["ci"][1] <= 0 else "판정 불가"),
        }
    return results


# '차이가 threshold 이하인가'를 구간으로 판정한다. 상한 ≤ threshold면 지지, 하한 > threshold면 기각, 구간이
# 기준을 걸치면 판정 불가(공통 규칙의 세 가지 판정). H5에서 threshold = −5.0으로 부른다.
def _verdict_le(diff: dict | None, threshold: float) -> str:
    """구간 상한이 ``threshold`` 이하이면(차이가 기준보다 확실히 낮으면) 지지로 판정한다."""
    if not diff or diff["ci"][0] is None:
        return "판정 불가"
    lo, hi = diff["ci"]
    if hi <= threshold:
        return "지지"
    if lo > threshold:
        return "기각"
    return "판정 불가"


# H7: 묵은 라벨 MAE − 신선 라벨 MAE의 구간. 두 집단은 서로 다른 단위라 짝지을 수 없으므로 각 집단을 독립으로
# 선수 단위 재표집한다(규칙의 '독립 부트스트랩 차이'). 하한 ≥ 5 W면 지지, 상한 < 5 W면 기각.
def _h7(target: pd.DataFrame, p: pd.Series, n=BOOTSTRAP_N, seed=SEED) -> dict:
    """묵은 라벨 MAE − 신선 라벨 MAE를 집단별 독립 선수 재표집으로 재고 구간과 판정을 돌려준다."""
    err = (p - target["label_ftp"]).abs()
    fresh = err[target["fresh"] & err.notna()]
    stale = err[~target["fresh"] & err.notna()]
    # 어느 집단이든 단위가 H7_MIN_UNITS(20)개 미만이면 구간이 의미가 없어 판정 불가로 둔다(규칙 변경 10으로
    # 규칙에 적은 하한).
    if len(fresh) < H7_MIN_UNITS or len(stale) < H7_MIN_UNITS:
        return {"diff_stale_minus_fresh": None, "verdict": "판정 불가"}
    rng = np.random.default_rng(seed)
    fa = target.loc[fresh.index, "athlete"]
    sa = target.loc[stale.index, "athlete"]
    gf = {a: e.to_numpy() for a, e in fresh.groupby(fa)}
    gs = {a: e.to_numpy() for a, e in stale.groupby(sa)}
    kf, ks = list(gf), list(gs)
    diffs = []
    for _ in range(n):
        f = np.concatenate([gf[kf[i]] for i in rng.integers(0, len(kf), len(kf))]).mean()
        s = np.concatenate([gs[ks[i]] for i in rng.integers(0, len(ks), len(ks))]).mean()
        diffs.append(s - f)
    lo, hi = float(np.quantile(diffs, 0.025)), float(np.quantile(diffs, 0.975))
    verdict = "지지" if lo >= 5 else ("기각" if hi < 5 else "판정 불가")
    return {
        "diff_stale_minus_fresh": {"point": float(stale.mean() - fresh.mean()), "ci": [lo, hi]},
        "verdict": verdict,
    }


# 보고용 집단별 MAE(판정 없음): 성별, 나이 10년 단위, 창 안 라이딩 수 구간(8~12, 13~20, 21~30, 31 이상;
# MIN_RIDES가 8이라 첫 구간이 8부터). Codex 3차 검토에서 집단별 보고 누락을 지적받아 추가했다. 출생 연도가
# 엉뚱한 선수가 있어 나이 구간은 그대로 믿을 수 없다(docs/experiments.md).
def breakdown(pred: pd.Series, u: pd.DataFrame) -> dict:
    """성별, 나이 10년 단위, 창 안 라이딩 수별 MAE를 낸다(보고용, 판정 없음)."""
    ok = pred.notna() & u["label_ftp"].notna()
    err = (pred[ok] - u.loc[ok, "label_ftp"]).abs()
    t = u.loc[ok].assign(err=err)
    gender = t["gender_m"].map({1.0: "M", 0.0: "F"}).fillna("unknown")
    decade = (t["age"] // 10 * 10).fillna(-1).astype(int).astype(str).replace("-1", "unknown")
    bucket = pd.cut(t["n_rides"], [0, 12, 20, 30, 10_000], labels=["8-12", "13-20", "21-30", "31+"])

    def groups(key):
        return {
            str(k): {"n": int(len(g)), "mae": float(g["err"].mean())}
            for k, g in t.groupby(key, observed=True)
        }

    return {"gender": groups(gender), "age_decade": groups(decade), "rides_in_window": groups(bucket)}


# evaluate 결과 dict를 사람이 읽는 Markdown 보고서(reports/ftp/*.md)로 만든다. 방법별 표(적용 비율, MAE·중앙값
# AE·편향과 구간, MAPE, R1과의 짝지은 차이, 신선/묵은 MAE, L_CP 기준 MAE), H5·H6·H7 판정, 집단별 MAE 순서다.
# 내부 함수 ci()는 '점추정 [하한, 상한]' 문자열을 만든다.
def to_markdown(r: dict) -> str:
    def ci(d):
        if not d or d.get("point") is None:
            return "-"
        return f"{d['point']:.1f} [{d['ci'][0]:.1f}, {d['ci'][1]:.1f}]"

    lines = [
        f"# FTP 추정 결과: {r['split']}",
        "",
        f"- 예측 단위 {r['n_units']}개(선수 {r['n_athletes']}명), train 단위 {r['n_train']}개, "
        f"신선 라벨 {r['fresh_units']}개",
        f"- L_CP가 L_FTP와 1 W 넘게 다른 단위 {r['label_cp_differs']}개, L_CP 없음 {r['label_cp_missing']}개",
        "",
        "| 방법 | 적용 | MAE (W) | 중앙값 AE | MAPE | 편향 | MAE-R1 (짝지음) | MAE 신선/묵은 | MAE vs L_CP |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for name, m in r["methods"].items():
        if "skipped" in m:
            lines.append(f"| {name} | 건너뜀 ({m['skipped']}) | | | | | | | |")
            continue
        mape = m["mape"]
        mape_s = f"{mape['point'] * 100:.1f}%" if mape.get("point") is not None else "-"
        cells = [
            name,
            f"{m['coverage'] * 100:.0f}%",
            ci(m["mae"]),
            ci(m["median_ae"]),
            mape_s,
            ci(m["bias"]),
            ci(m.get("paired_mae_minus_r1")),
            f"{ci(m['mae_fresh'])} / {ci(m['mae_stale'])}",
            ci(m["mae_vs_label_cp"]),
        ]
        lines.append("| " + " | ".join(cells) + " |")
    for h in ("H5", "H6", "H7"):
        if h in r:
            lines.append("")
            lines.append(f"- **{h}**: {json.dumps(r[h], ensure_ascii=False, default=str)}")
    if "breakdown" in r:
        lines += ["", "## 보고: 집단별 MAE (판정 없음)", ""]
        for name, b in r["breakdown"].items():
            for key, groups in b.items():
                cells = ", ".join(f"{k}: {v['mae']:.1f} W (n={v['n']})" for k, v in groups.items())
                lines.append(f"- {name} / {key}: {cells}")
    return "\n".join(lines) + "\n"


# 명령행 진입점. build는 --features(JSONL), --index(rides 인덱스 폴더), --sample(매니페스트), --out(parquet)을
# 받고, evaluate는 --units, --split(dev/test), --adopt, --out(확장자 없는 경로)을 받아 .json과 .md를 쓴다.
def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("build")
    s.add_argument("--features", type=Path, required=True)
    s.add_argument("--index", type=Path, required=True)
    s.add_argument("--sample", type=Path, required=True)
    s.add_argument("--out", type=Path, required=True)
    s = sub.add_parser("evaluate")
    s.add_argument("--units", type=Path, required=True)
    s.add_argument("--split", choices=["dev", "test"], required=True)
    s.add_argument(
        "--adopt", default=None, help="dev에서 고른 채택 후보 이름(봉인 test를 돌릴 때는 반드시 필요)"
    )
    s.add_argument("--out", type=Path, required=True, help="확장자를 뺀 출력 경로(.json과 .md를 쓴다)")
    a = p.parse_args(argv)
    if a.cmd == "build":
        cmd_build(a.features, a.index, a.sample, a.out)
    else:
        # 봉인 test는 dev에서 채택 후보를 먼저 정한 뒤 한 번만 재야 하므로, --adopt 없이 test를 돌리는 것을
        # 막는다. test 결과를 보고 채택 후보를 고르는 일을 도구 수준에서 차단하려는 장치다.
        if a.split == "test" and not a.adopt:
            raise SystemExit("--adopt is required for the sealed test run (choose it on dev first)")
        units = pd.read_parquet(a.units)
        r = evaluate(units, a.split, a.adopt)
        a.out.parent.mkdir(parents=True, exist_ok=True)
        Path(f"{a.out}.json").write_text(
            json.dumps(r, indent=1, ensure_ascii=False, default=str), encoding="utf-8"
        )
        Path(f"{a.out}.md").write_text(to_markdown(r), encoding="utf-8")
        print(f"wrote {a.out}.json and {a.out}.md")


if __name__ == "__main__":
    main()
