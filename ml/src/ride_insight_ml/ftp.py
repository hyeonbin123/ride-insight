"""3단계: 최근 6주 라이딩으로 FTP를 추정한다(docs/experiments.md, 3단계).

하위 명령:

* ``build``     창 특징과 라벨을 붙인 예측 단위(선수, 기준일)를 만든다
* ``evaluate``  후보를 train으로 맞추고, 한 분할에서 MAE 등을 선수 단위 부트스트랩
                구간과 함께 보고한다
* ``calibrate`` R1에 붙일 창 안 라이딩 수 구간별 80% 예측 구간을 train 단위로 보정하고
                dev 단위로 포함률을 확인한다(봉인 test는 쓰지 않는다)
* ``baseline``  라벨 지속성 기준선 P0(같은 선수의 28일 이상 앞선 가장 최근 단위의 L_FTP)를
                dev 단위에서 R1과 같은 단위로 비교해 보고한다(판정 없음, 봉인 test는 거부한다)

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
# calibrate: <out>.json, <out>.md(reports/ftp/r1-intervals.*, docs/experiments.md 3단계 'R1 조건부 80% 예측
# 구간'). baseline: <out>.json, <out>.md(reports/ftp/dev-persistence.*, docs/experiments.md 3단계 '사후
# 보고: 라벨 지속성 기준선 P0').
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
# 주의: 돌려주는 값은 날짜가 아니라 그 라이딩의 시작 시각(Timestamp) 자체다. 그래서 창의 마지막 라이딩이
# 곧 기준 라이딩이고, build_units의 days_since_last는 모든 단위에서 0이 된다(docs/experiments.md 규칙
# 변경 15).
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
            # - n_rides·hours·np_mean·np_max: 창 안 훈련량
            # - days_since_last('마지막 라이딩까지 일수'): 규칙은 최근성 특징으로 등록했지만, 기준일이
            #   창의 마지막 라이딩 시각(reference_dates)이라 구성상 모든 단위에서 0이다. 그래서 모델에
            #   최근성 정보는 들어가지 않았다(docs/experiments.md 규칙 변경 15). 3단계 측정이 이 정의로
            #   끝났으므로 바꾸지 않고, 다시 정의하려면(예: 기준 라이딩 직전 라이딩까지 일수) 다음 측정
            #   규칙에 먼저 등록한다
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
    # 줄 끝은 OS와 무관하게 LF(newline="\n"). 지정하지 않으면 Windows에서 CRLF가 된다(main의 보고서와 같다).
    out.with_suffix(".summary.json").write_text(json.dumps(summary, indent=1), encoding="utf-8", newline="\n")
    print(
        f"rides={len(rides)} athletes={rides['athlete'].nunique()} units={len(units)} "
        f"by_split={units['split'].value_counts().to_dict()} fresh={int(units['fresh'].sum())} "
        f"with_20m={int(units['mmp_20m'].notna().sum())} with_weight={int(units['weight'].notna().sum())}"
    )


# ----------------------------------------------------------------------------- 후보 방법

# FEATURES: 모델 M1·M2가 쓰는 기본 특징 23개(MMP 16 + 훈련량 4 + days_since_last + 성별·나이 2). 체중은 들어
# 있지 않다. 규칙에는 체중을 기본 특징으로 적었지만 구현은 M1b에만 넣었고, 측정 뒤에 발견해 다시 돌리지 않고
# 기록만 했다(규칙 변경 6).
# days_since_last는 구성상 모든 단위에서 0인 상수라(build_units 주석) 실질 특징은 22개다. 상수 열은 표준화 뒤
# 0이 되어 M2(Ridge) 계수가 0이고, LightGBM(M1)은 값이 하나뿐인 열로 분기하지 못한다. 열을 빼고 train으로 다시
# 맞춰도 dev 예측이 같아 3단계 수치에는 영향이 없다(규칙 변경 15). 열을 빼는 것도 측정 코드 변경이라 지금은
# 두고, 다음 측정 규칙에 등록한 뒤 정한다.
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


# 모델 입력 행렬을 만든다. 기본은 FEATURES 23열(그중 days_since_last는 상수라 실질 22열).
# with_weight=True(M1b)이면 체중당 파워(W/kg) 5분·20분·60분과 체중을 더한다. 호출 위치: LgbmCandidate,
# RidgeCandidate._x, ftp_export.py(Kotlin 대조용 모델이 같은 열 순서를 쓰도록).
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


# ----------------------------------------------------------------------------- calibrate: R1 80% 예측 구간

# R1 조건부 80% 예측 구간의 상수. docs/experiments.md 3단계 'R1 조건부 80% 예측 구간'(규칙 변경 18)에 측정
# 전에 커밋한 값이다.
# - INTERVAL_TAIL_PCT: 양쪽 꼬리 각각의 비율(%). 10이면 q_lo = r(⌊0.10·(n+1)⌋), q_hi = r(⌈0.90·(n+1)⌉)이고
#   구간 수준은 80%(INTERVAL_LEVEL)다. 백분율을 정수로 둬서 순서 통계량 번호를 정수 산술로 구한다. 0.1·(n+1)을
#   부동소수로 계산하면 n+1이 10의 배수일 때 반올림 오차로 한 칸 어긋날 수 있다.
# - RIDES_BUCKETS: 창 안 파워 라이딩 수 구간(이름, 최소, 최대; 최대가 None이면 위로 열림). 3단계 집단별
#   보고(breakdown)의 구간과 같다. MIN_RIDES(8) 미만은 측정 조건 밖이라 어느 구간에도 들지 않는다. 서버는 이
#   이름과 경계를 보고서 JSON에서 그대로 읽는다.
# - H14_BAND: H14 '보정 유지'의 포함률 범위. dev 포함률의 선수 단위 95% 구간이 이 안에 있고 80%를 포함해야
#   한다.
# - INTERVALS_OUT_SUFFIX: --out 이름이 이것으로 끝나야 한다(2026-10-30 추가, 규칙의 값이 아니라 도구의 보호
#   장치). `--out ../reports/ftp/dev`처럼 잘못 준 경로가 reports/ftp/dev.*·test.* 같은 3단계 기록을 덮어쓰지
#   않게 하려는 것이다. 기록 명령의 출력 이름 r1-intervals(reproduce도 같은 이름)는 이 규칙을 만족한다.
INTERVAL_TAIL_PCT = 10
INTERVAL_LEVEL = (100 - 2 * INTERVAL_TAIL_PCT) / 100
RIDES_BUCKETS = (("8-12", 8, 12), ("13-20", 13, 20), ("21-30", 21, 30), ("31+", 31, None))
H14_BAND = (0.76, 0.84)
INTERVALS_OUT_SUFFIX = "-intervals"


def rides_bucket(n_rides: int) -> str | None:
    """창 안 파워 라이딩 수가 속한 구간 이름(RIDES_BUCKETS)을 돌려준다. 8개 미만이면 None."""
    for name, lo, hi in RIDES_BUCKETS:
        if n_rides >= lo and (hi is None or n_rides <= hi):
            return name
    return None


# split conformal의 하·상한. 잔차를 작은 순서로 놓고(1부터 센다) k_lo = ⌊0.10·(n+1)⌋번째와 k_hi =
# ⌈0.90·(n+1)⌉번째 값을 고른다. 보정 단위와 새 단위가 교환 가능하면 새 단위의 잔차가 k_lo번째보다 작을 확률과
# k_hi번째보다 클 확률이 각각 10% 이하라, 양 끝을 포함한 구간의 포함률이 80% 이상이 된다(표준 split
# conformal). n < 9면 k_lo가 0이 되거나 k_hi가 n을 넘어 정의되지 않으므로 None을 돌려준다(규칙: 그 라이딩 수
# 구간은 구간을 내지 않는다).
# 입력: 잔차 배열(순서 무관). 출력: {"k_lo", "k_hi", "q_lo", "q_hi"} 또는 None.
def conformal_bounds(residuals) -> dict | None:
    """잔차의 split conformal 하·상한 순서 통계량(1부터 센 번호와 값). 정의되지 않으면 None."""
    r = np.sort(np.asarray(residuals, dtype=float))
    n = len(r)
    k_lo = INTERVAL_TAIL_PCT * (n + 1) // 100
    # ⌈a / b⌉ = −⌊−a / b⌋ (정수 산술)
    k_hi = -(-(100 - INTERVAL_TAIL_PCT) * (n + 1) // 100)
    if k_lo < 1 or k_hi > n:
        return None
    return {"k_lo": int(k_lo), "k_hi": int(k_hi), "q_lo": float(r[k_lo - 1]), "q_hi": float(r[k_hi - 1])}


# H14 판정(dev 참고 판정, 규칙 변경 18). coverage는 athlete_bootstrap 결과(포함률의 선수 단위 95% 구간).
# 구간이 H14_BAND 안에 있고 80%를 포함하면 '보정 유지', 상한 < 76% 또는 하한 > 84%면 '보정 실패', 그 밖(경계를
# 걸치거나, 76~84% 안이지만 80%를 포함하지 않거나, 구간이 없음)은 '판정 불가'다.
def _verdict_h14(coverage: dict) -> str:
    """dev 포함률의 선수 단위 구간으로 H14를 판정한다."""
    lo, hi = coverage["ci"]
    if lo is None or hi is None:
        return "판정 불가"
    band_lo, band_hi = H14_BAND
    if hi < band_lo or lo > band_hi:
        return "보정 실패"
    if band_lo <= lo and hi <= band_hi and lo <= INTERVAL_LEVEL <= hi:
        return "보정 유지"
    return "판정 불가"


# calibrate 하위 명령의 본체. 입력은 build가 만든 예측 단위 표(units.parquet). 3단계와 같이 20분 MMP가 없는
# 단위를 빼고, train 단위의 잔차 L_FTP − R1로 라이딩 수 구간마다 conformal_bounds를 구한 뒤, dev 단위에 [R1 +
# q_lo, R1 + q_hi](양 끝 포함)를 붙여 L_FTP가 들어가는 비율(포함률)을 선수 단위 부트스트랩 구간과 함께 잰다.
# 왜 train으로 보정하나: R1은 학습할 매개변수가 없어 train 단위가 R1을 만드는 데 쓰이지 않았다. 그래서 train은
# R1에 대해 '처음 보는' 보정 세트가 되고, dev는 보정에 쓰이지 않은 확인 세트가 된다.
# 봉인 test 단위는 맨 처음에 버린다(3단계에서 한 번 쓴 세트라 이 절에서 쓰지 않는다는 규칙). 분할을 고르는
# 인자를 두지 않은 것도 같은 이유다. 출력에도 test 단위 수 같은 값을 남기지 않는다.
# 같은 선수의 단위끼리는 독립이 아니어서 split conformal의 보장이 단위 수준에서 그대로 성립하지 않는다. 그래서
# 포함률 구간은 선수를 다시 뽑는 athlete_bootstrap으로 낸다(공통 절 규칙 변경 16).
# 출력 dict: 보정 표(buckets: 이름·경계·train 단위 수·순서 번호·q_lo·q_hi·폭·그 구간의 dev 확인), dev 전체
# 확인(포함률, 폭 중앙값, 신선/묵은 라벨별 포함률, 지금 안내를 대칭 구간 R1 ± dev MAE로 읽었을 때의 포함률),
# H14 판정. 서버는 이 JSON의 buckets와 H14를 읽는다(서비스 반영은 '보정 유지'일 때만).
def calibrate(units: pd.DataFrame, n: int = BOOTSTRAP_N, seed: int = SEED) -> dict:
    """train 단위로 라이딩 수 구간별 R1 80% 구간을 보정하고 dev 단위로 포함률을 확인한다(test는 쓰지 않음)."""
    has_20m = units["mmp_20m"].notna()
    train = units[(units["split"] == "train") & has_20m]
    dev = units[(units["split"] == "dev") & has_20m]
    excluded = {s: int(((units["split"] == s) & ~has_20m).sum()) for s in ("train", "dev")}
    train_bucket = train["n_rides"].map(rides_bucket)
    dev_bucket = dev["n_rides"].map(rides_bucket)
    train_resid = train["label_ftp"] - rule_r1(train)
    dev_r1 = rule_r1(dev)
    # 구간이 없는 dev 단위(라이딩 수 구간의 train 단위가 9개 미만)는 lo·hi가 NaN으로 남아 포함률에서 빠진다.
    lo = pd.Series(np.nan, index=dev.index)
    hi = pd.Series(np.nan, index=dev.index)
    buckets = []
    for name, min_rides, max_rides in RIDES_BUCKETS:
        in_train = train_bucket == name
        in_dev = dev_bucket == name
        b = conformal_bounds(train_resid[in_train])
        entry = {"name": name, "min_rides": min_rides, "max_rides": max_rides, "n_train": int(in_train.sum())}
        if b is None:
            entry.update(k_lo=None, k_hi=None, q_lo=None, q_hi=None, width=None)
        else:
            entry.update(b, width=b["q_hi"] - b["q_lo"])
            lo.loc[in_dev] = dev_r1[in_dev] + b["q_lo"]
            hi.loc[in_dev] = dev_r1[in_dev] + b["q_hi"]
        buckets.append((entry, in_dev))
    has = lo.notna()
    covered = ((dev["label_ftp"] >= lo) & (dev["label_ftp"] <= hi)).astype(float)

    # mask(dev 단위의 부분 집합) 안에서 구간이 있는 단위의 포함률과 선수 단위 95% 구간.
    def coverage(mask: pd.Series) -> dict:
        m = mask & has
        return athlete_bootstrap(covered[m], dev.loc[m, "athlete"], np.mean, n, seed)

    for entry, in_dev in buckets:
        entry["dev"] = {
            "n_units": int(in_dev.sum()),
            "n_athletes": int(dev.loc[in_dev, "athlete"].nunique()),
            "coverage": coverage(in_dev),
        }
    overall = coverage(pd.Series(True, index=dev.index))
    fresh = dev["fresh"].astype(bool)
    width = (hi - lo)[has]
    # 참고(보고만): 지금 안내의 dev MAE를 대칭 구간 R1 ± MAE로 읽었을 때의 포함률. MAE는 같은 dev 단위에서
    # 다시 재고, 서버가 보여 주는 값처럼 가장 가까운 정수로 반올림한다(0.5는 올림, Kotlin roundToInt와 같음).
    abs_err = (dev_r1 - dev["label_ftp"]).abs()
    mae = float(abs_err.mean()) if len(dev) else None
    half = int(np.floor(mae + 0.5)) if mae is not None else None
    symmetric = athlete_bootstrap(
        (abs_err <= half).astype(float) if half is not None else abs_err, dev["athlete"], np.mean, n, seed
    )
    return {
        "method": "R1 + split conformal interval per rides-in-window bucket (Mondrian)",
        "level": INTERVAL_LEVEL,
        "residual": "L_FTP - R1",
        "calibrated_on": "train",
        "checked_on": "dev",
        "n_train": int(len(train)),
        "n_dev": int(len(dev)),
        "excluded_no_20m": excluded,
        "buckets": [entry for entry, _ in buckets],
        "dev": {
            "n_units": int(has.sum()),
            "n_athletes": int(dev.loc[has, "athlete"].nunique()),
            "n_without_interval": int((~has).sum()),
            "coverage": overall,
            "median_width": float(width.median()) if len(width) else None,
            "fresh": coverage(fresh),
            "stale": coverage(~fresh),
            "symmetric_dev_mae": {"mae_watts": mae, "half_width_watts": half, "coverage": symmetric},
        },
        "H14": {"coverage": overall, "verdict": _verdict_h14(overall)},
    }


# calibrate 결과 dict를 사람이 읽는 Markdown 보고서(reports/ftp/r1-intervals.md)로 만든다. 라이딩 수 구간별
# 보정 표와 dev 포함률, dev 전체 포함률·폭, 신선/묵은 라벨, 대칭 구간 참고값, H14 판정과 사후 성격·한계
# 순서다.
def calibration_to_markdown(r: dict) -> str:
    def pct(c):
        if not c or c.get("point") is None:
            return "-"
        return f"{c['point'] * 100:.1f}% [{c['ci'][0] * 100:.1f}, {c['ci'][1] * 100:.1f}]"

    def watts(x):
        return "-" if x is None else f"{x:+.1f}"

    d = r["dev"]
    sym = d["symmetric_dev_mae"]
    band = f"[{H14_BAND[0] * 100:.0f}%, {H14_BAND[1] * 100:.0f}%]"
    median = "-" if d["median_width"] is None else f"{d['median_width']:.1f}"
    lines = [
        "# R1 조건부 80% 예측 구간: train 보정, 개발 세트(dev) 확인",
        "",
        "- 방법: 창 안 파워 라이딩 수 구간마다 train 잔차(L_FTP − R1)의 split conformal 분위로 "
        f"구간 [R1 + q_lo, R1 + q_hi]를 만든다(수준 {r['level'] * 100:.0f}%, 양 끝 포함). "
        "규칙은 docs/experiments.md 3단계 'R1 조건부 80% 예측 구간'",
        f"- 보정 train 단위 {r['n_train']}개, 확인 dev 단위 {r['n_dev']}개(20분 기록이 없어 뺀 단위 train "
        f"{r['excluded_no_20m']['train']}개·dev {r['excluded_no_20m']['dev']}개). 봉인 test는 쓰지 않았다",
        "",
        "| 창 안 라이딩 수 | train 단위 | q_lo (W) | q_hi (W) | 폭 (W) | dev 단위 (선수) "
        "| dev 포함률 [95%] |",
        "|---|---|---|---|---|---|---|",
    ]
    for b in r["buckets"]:
        width = "-" if b["width"] is None else f"{b['width']:.1f}"
        cells = [
            b["name"],
            str(b["n_train"]),
            watts(b["q_lo"]),
            watts(b["q_hi"]),
            width,
            f"{b['dev']['n_units']} ({b['dev']['n_athletes']})",
            pct(b["dev"]["coverage"]),
        ]
        lines.append("| " + " | ".join(cells) + " |")
    mae = "-" if sym["mae_watts"] is None else f"{sym['mae_watts']:.2f}"
    lines += [
        "",
        f"- dev 전체(구간이 있는 단위 {d['n_units']}개, 선수 {d['n_athletes']}명): "
        f"포함률 {pct(d['coverage'])}, 구간 폭 중앙값 {median} W. "
        f"구간이 없는 라이딩 수의 dev 단위 {d['n_without_interval']}개",
        f"- 신선 라벨 {pct(d['fresh'])}(단위 {d['fresh']['n_units']}개) / "
        f"묵은 라벨 {pct(d['stale'])}(단위 {d['stale']['n_units']}개)",
        f"- 참고: 지금 안내의 dev MAE({mae} W)를 대칭 구간 R1 ± {sym['half_width_watts']} W로 읽으면 "
        f"dev 포함률 {pct(sym['coverage'])}",
        "",
        f"- **H14 (dev 참고 판정)**: {r['H14']['verdict']}. 포함률의 선수 단위 95% 구간이 {band} "
        f"안이고 {r['level'] * 100:.0f}%를 포함하면 '보정 유지', 상한 < {H14_BAND[0] * 100:.0f}% "
        f"또는 하한 > {H14_BAND[1] * 100:.0f}%면 '보정 실패', 그 밖은 '판정 불가'",
        "- 사후 성격: dev의 R1 잔차 분포는 2026-10-19 읽기 전용 코드 검토가 먼저 계산해 봤다. "
        "정식 판정은 처음 보는 선수의 새 봉인 세트에서만 할 수 있다",
        "- 한계: 포함률은 OpenData(GoldenCheetah 사용자) 선수의 개발 세트 기준이다. "
        "구간은 선수가 설정해 둔 FTP 값(L_FTP)이 들 범위이지 생리적 FTP의 범위가 아니다",
    ]
    return "\n".join(lines) + "\n"


# ----------------------------------------------------------------------------- baseline: 라벨 지속성 P0

# 라벨 지속성 기준선의 상수. docs/experiments.md 3단계 '사후 보고: 라벨 지속성 기준선 P0'(규칙 변경 20)에
# 도구보다 먼저 커밋한 값이다.
# - P0_MIN_GAP_DAYS: P0의 단위는 기준일이 이 일수(날짜 차이, 경계 포함) 이상 앞서야 한다. '적어도 4주 전에
#   설정돼 있던 값'이라는 뜻이다. 3단계 기준일 간격(STEP_DAYS)도 28일이라 이 데이터에서는 같은 선수의 바로
#   앞 단위가 곧 P0의 단위가 되지만, 서로 다른 규칙이라 상수를 따로 둔다(한쪽을 바꿔도 다른 쪽은 그대로).
# - PERSISTENCE_SPLITS: 이 보고가 받는 분할. 봉인 test는 3단계에서 한 번 썼으므로 받지 않고, P0는 학습할
#   것이 없어 train도 쓰지 않는다.
# - PERSISTENCE_OUT_SUFFIX: --out 이름이 이것으로 끝나야 한다. reports/ftp/dev.*·test.* 같은 3단계 기록을
#   이 명령이 덮어쓰지 않게 하려는 것이다.
P0_MIN_GAP_DAYS = 28
PERSISTENCE_SPLITS = ("dev",)
PERSISTENCE_OUT_SUFFIX = "-persistence"


# 선수마다 단위의 위치(0부터 센 행 번호)를 기준일 순서로 정렬해, (위치 배열, 그 순서의 기준일 배열)을
# 하나씩 내보낸다. previous_unit_labels와 _previous_closer_than_gap이 같은 순서를 쓰게 하려는 도우미다.
# 인덱스 이름표가 아니라 위치로 다뤄 인덱스가 겹치거나 섞여 있어도 결과를 원래 행에 정확히 되돌린다. 같은
# 기준일끼리는 입력 순서를 지킨다(안정 정렬).
def _by_athlete_in_date_order(units: pd.DataFrame):
    dates = pd.to_datetime(units["ref_date"]).to_numpy()
    for pos in units.groupby("athlete", sort=False).indices.values():
        pos = pos[np.argsort(dates[pos], kind="stable")]
        yield pos, dates[pos]


# 단위마다 P0 = 같은 선수의 단위 중 기준일이 min_gap_days일 이상 앞선 단위 가운데 기준일이 가장 늦은 단위의
# L_FTP를 구한다. 그런 단위가 없으면 NaN(선수의 첫 단위가 그렇다).
# 입력: athlete·ref_date('YYYY-MM-DD')·label_ftp 열이 있는 단위 표. 분할은 호출하는 쪽이 먼저 거른다.
# 출력: 입력 인덱스에 맞춘 float Series(행 순서와 무관하다).
# 방법: 선수마다 정렬한 기준일 배열에서 '기준일 − min_gap_days' 이하인 마지막 위치를 이분 탐색
# (np.searchsorted, side="right")으로 찾는다. 같은 선수 안에서만 찾으므로 다른 선수의 단위는 P0가 될 수 없다.
# 같은 선수의 기준일이 같은 단위가 여럿이면 정렬 순서의 뒤쪽 단위를 고르지만, build_units의 기준일은 28일
# 이상 떨어져 있어 실제 데이터에서는 그런 일이 없다.
def previous_unit_labels(units: pd.DataFrame, min_gap_days: int = P0_MIN_GAP_DAYS) -> pd.Series:
    """같은 선수의 단위 중 기준일이 ``min_gap_days``일 이상 앞선 가장 최근 단위의 L_FTP(없으면 NaN)."""
    labels = units["label_ftp"].to_numpy(dtype=float)
    out = np.full(len(units), np.nan)
    gap = np.timedelta64(min_gap_days, "D")
    for pos, dates in _by_athlete_in_date_order(units):
        k = np.searchsorted(dates, dates - gap, side="right") - 1
        out[pos] = np.where(k >= 0, labels[pos][np.maximum(k, 0)], np.nan)
    return pd.Series(out, index=units.index)


# 같은 선수의 바로 앞 단위(기준일 순서)가 min_gap_days일보다 가까운 단위를 True로 표시한다(선수의 첫 단위는
# False). 이런 단위는 P0를 그보다 앞 단위에서 가져오거나 P0가 없게 된다. 보고의 이 수가 0이면 이 데이터의 P0는
# '바로 앞 단위의 L_FTP'(2026-10-19 읽기 전용 코드 검토가 먼저 계산할 때 쓴 정의)와 같다.
def _previous_closer_than_gap(units: pd.DataFrame, min_gap_days: int = P0_MIN_GAP_DAYS) -> pd.Series:
    out = np.zeros(len(units), dtype=bool)
    gap = np.timedelta64(min_gap_days, "D")
    for pos, dates in _by_athlete_in_date_order(units):
        out[pos[1:]] = (dates[1:] - dates[:-1]) < gap
    return pd.Series(out, index=units.index)


# baseline 하위 명령의 본체. 입력은 build가 만든 예측 단위 표(units.parquet)다.
# 1) 받은 표에서 split(dev만 허용) 단위만 남긴다. 봉인 test와 train 단위는 여기서 버려 P0의 단위로도 쓰이지
#    않는다(분할이 선수 단위라 dev 선수의 단위는 모두 dev에 있다). dev가 아닌 분할은 ValueError로 거부한다.
# 2) 남은 dev 단위 전체에서 P0를 구한다. 20분 MMP가 없는 단위도 L_FTP는 있으므로 P0의 단위가 될 수 있다.
# 3) 평가 단위는 3단계 dev 평가(evaluate)와 같이 20분 MMP가 있는 단위다. 그래서 모든 평가 단위에서 R1이
#    정의되고, 'R1 MAE(평가 단위 전체)'가 reports/ftp/dev.json의 R1 MAE와 같은 계산이 된다.
# 4) P0가 있는 단위에서 P0와 R1의 MAE·중앙값 절대 오차·편향, 짝지은 차이 P0 − R1(paired_mae_difference),
#    신선/묵은 라벨별 MAE, L_FTP가 P0와 다른 단위의 비율을 낸다. 구간은 모두 athlete_bootstrap(선수 단위
#    재표집, 2,000회, seed 0)이다.
# 판정은 없다(verdict None). P0는 3단계 후보(candidates)에 넣지 않는다. 넣으면 'dev MAE 최소 = 채택 후보'
# 규칙이 설정값을 그대로 내는 P0를 고르게 되는데, P0는 설정값이 없는 처음 보는 선수에게 쓸 수 없어 FTP 추정
# 방법이 아니다. 입력 표는 바꾸지 않는다(evaluate 결과에 영향 없음).
def persistence_baseline(
    units: pd.DataFrame, split: str = "dev", n: int = BOOTSTRAP_N, seed: int = SEED
) -> dict:
    """dev 단위에서 라벨 지속성 기준선 P0를 R1과 같은 단위로 비교해 보고한다(판정 없음)."""
    if split not in PERSISTENCE_SPLITS:
        raise ValueError(
            f"the persistence baseline runs on {', '.join(PERSISTENCE_SPLITS)} only, not {split!r} "
            "(the sealed test split was used once in stage 3, and P0 has nothing to learn from train)"
        )
    pool = units[units["split"] == split]
    p0_all = previous_unit_labels(pool)
    closer_all = _previous_closer_than_gap(pool)
    has_20m = pool["mmp_20m"].notna()
    target = pool[has_20m]
    r1 = rule_r1(target)
    p0 = p0_all[has_20m]
    has = p0.notna()
    u = target[has]
    p0u, r1u = p0[has], r1[has]

    # 한 예측의 MAE(선수 단위 구간).
    def mae(pred: pd.Series, frame: pd.DataFrame) -> dict:
        return athlete_bootstrap((pred - frame["label_ftp"]).abs(), frame["athlete"], np.mean, n, seed)

    # 한 예측의 MAE·중앙값 절대 오차·편향(예측 − L_FTP 평균). 3단계 summarize와 같은 지표다(MAPE·적용
    # 비율은 이 보고에 등록하지 않아 뺐다).
    def errors(pred: pd.Series, frame: pd.DataFrame) -> dict:
        err = pred - frame["label_ftp"]
        return {
            "mae": athlete_bootstrap(err.abs(), frame["athlete"], np.mean, n, seed),
            "median_ae": athlete_bootstrap(err.abs(), frame["athlete"], np.median, n, seed),
            "bias": athlete_bootstrap(err, frame["athlete"], np.mean, n, seed),
        }

    # 신선/묵은 라벨(3단계 정의, build_units의 fresh)로 나눈 P0·R1 MAE.
    fresh = u["fresh"].astype(bool)
    groups = {}
    for name, mask in (("fresh", fresh), ("stale", ~fresh)):
        g = u[mask]
        groups[name] = {
            "n_units": int(len(g)),
            "n_athletes": int(g["athlete"].nunique()),
            "p0_mae": mae(p0u[mask], g),
            "r1_mae": mae(r1u[mask], g),
        }
    # L_FTP − P0가 0이 아니면 '라벨이 P0와 다름'. 라벨은 반올림한 정수 임계값이거나 cp_setting이라 그대로
    # 비교한다. 0보다 크면 설정값을 올린 것, 작으면 내린 것이다.
    change = u["label_ftp"] - p0u
    labels_per_athlete = target.groupby("athlete")["label_ftp"].nunique()
    return {
        "method": "P0 = L_FTP of the same athlete's latest unit at least 28 days earlier",
        "split": split,
        "min_gap_days": P0_MIN_GAP_DAYS,
        "n_units": int(len(target)),
        "n_athletes": int(target["athlete"].nunique()),
        "excluded_no_20m": int((~has_20m).sum()),
        "with_p0": {
            "n_units": int(has.sum()),
            "n_athletes": int(u["athlete"].nunique()),
            "share": float(has.mean()) if len(has) else 0.0,
        },
        "n_without_p0": int((~has).sum()),
        "n_previous_closer_than_min_gap": int(closer_all[has_20m].sum()),
        "p0": errors(p0u, u),
        "r1": errors(r1u, u),
        "paired_mae_p0_minus_r1": paired_mae_difference(p0u, r1u, u, n, seed),
        "fresh": groups["fresh"],
        "stale": groups["stale"],
        "label_differs": {
            "share": athlete_bootstrap((change != 0).astype(float), u["athlete"], np.mean, n, seed),
            "n_units": int((change != 0).sum()),
            "n_up": int((change > 0).sum()),
            "n_down": int((change < 0).sum()),
        },
        "athletes_single_label": {
            "n_athletes": int((labels_per_athlete == 1).sum()),
            "of": int(len(labels_per_athlete)),
        },
        "r1_all_units_mae": mae(r1, target),
        "verdict": None,
    }


# persistence_baseline 결과 dict를 사람이 읽는 Markdown 보고서(reports/ftp/dev-persistence.md)로 만든다.
# 정의와 단위 수, P0·R1 표(MAE·중앙값 AE·편향과 신선/묵은 라벨 MAE), 짝지은 차이, 라벨이 P0와 다른 단위,
# 한 값인 선수, 3단계 dev 보고서와 같은 R1 MAE, 판정 없음과 한계 순서다. 1 W보다 작은 차이도 읽을 수 있게
# 수치는 소수 둘째 자리까지 쓴다(3단계 보고서는 첫째 자리).
def persistence_to_markdown(r: dict) -> str:
    def ci(d):
        if not d or d.get("point") is None:
            return "-"
        return f"{d['point']:.2f} [{d['ci'][0]:.2f}, {d['ci'][1]:.2f}]"

    def pct(d):
        if not d or d.get("point") is None:
            return "-"
        return f"{d['point'] * 100:.1f}% [{d['ci'][0] * 100:.1f}, {d['ci'][1] * 100:.1f}]"

    w, f, s, d = r["with_p0"], r["fresh"], r["stale"], r["label_differs"]
    single = r["athletes_single_label"]
    gap = r["min_gap_days"]
    lines = [
        "# 라벨 지속성 기준선 P0: 개발 세트(dev) 사후 보고 (판정 없음)",
        "",
        f"- P0 = 같은 선수의 단위 중 기준일이 {gap}일 이상 앞선 가장 최근 단위의 L_FTP(선수가 전에 "
        "설정해 둔 값). 규칙은 docs/experiments.md 3단계 '사후 보고: 라벨 지속성 기준선 P0'",
        f"- 평가 단위 {r['n_units']}개(선수 {r['n_athletes']}명, 20분 기록이 없어 뺀 단위 "
        f"{r['excluded_no_20m']}개). P0가 있는 단위 {w['n_units']}개(선수 {w['n_athletes']}명, "
        f"{w['share'] * 100:.1f}%), 없는 단위 {r['n_without_p0']}개, 바로 앞 단위가 {gap}일보다 가까운 "
        f"단위 {r['n_previous_closer_than_min_gap']}개. 봉인 test는 쓰지 않았다",
        "",
        f"| 방법 (P0가 있는 단위 {w['n_units']}개) | MAE (W) [95%] | 중앙값 AE (W) [95%] | 편향 (W) [95%] "
        "| MAE 신선 / 묵은 라벨 (W) |",
        "|---|---|---|---|---|",
    ]
    for name, key in (("P0 전 설정값", "p0"), ("R1 20분×0.95", "r1")):
        m = r[key]
        cells = [
            name,
            ci(m["mae"]),
            ci(m["median_ae"]),
            ci(m["bias"]),
            f"{ci(f[f'{key}_mae'])} / {ci(s[f'{key}_mae'])}",
        ]
        lines.append("| " + " | ".join(cells) + " |")
    lines += [
        "",
        f"- 짝지은 MAE 차이 P0 − R1: {ci(r['paired_mae_p0_minus_r1'])} W",
        f"- 신선 라벨 단위 {f['n_units']}개(선수 {f['n_athletes']}명) / 묵은 라벨 단위 {s['n_units']}개"
        f"(선수 {s['n_athletes']}명)",
        f"- L_FTP가 P0와 다른 단위 {d['n_units']}개, 비율 {pct(d['share'])}. 그중 P0보다 높은 단위 "
        f"{d['n_up']}개, 낮은 단위 {d['n_down']}개",
        f"- 평가 단위의 L_FTP가 모두 한 값인 선수 {single['n_athletes']}명 / {single['of']}명",
        f"- 참고: 평가 단위 전체의 R1 MAE {ci(r['r1_all_units_mae'])} W(3단계 dev 보고서와 같은 단위·같은 "
        "계산)",
        "",
        "- 판정 없음: 2026-10-19 읽기 전용 코드 검토가 같은 개발 세트로 이 기준선을 먼저 계산해 봤다"
        "(값을 본 뒤의 보고)",
        "- 한계: P0는 FTP 추정 방법이 아니다. 설정값이 없는 처음 보는 선수에게 쓸 수 없고 수행력이 바뀌어도 "
        "따라가지 않는다. 3단계의 후보·수치·판정과 서비스는 그대로다",
    ]
    return "\n".join(lines) + "\n"


# 명령행 진입점. build는 --features(JSONL), --index(rides 인덱스 폴더), --sample(매니페스트), --out(parquet)을
# 받고, evaluate는 --units, --split(dev/test), --adopt, --out(확장자 없는 경로)을 받아 .json과 .md를 쓴다.
# calibrate는 --units와 --out(이름이 -intervals로 끝나야 한다)만 받는다(분할을 고르는 옵션이 없어 봉인
# test로는 돌릴 수 없다). baseline은 --units, --split(dev만 받는다), --out(이름이 -persistence로 끝나야
# 한다)을 받는다.
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
    # argparse는 help 문자열을 %-서식으로 처리하므로 글자 %는 %%로 쓴다.
    s = sub.add_parser(
        "calibrate",
        help="R1 80%% 예측 구간: train 단위로 보정하고 dev 단위로 확인한다(봉인 test는 쓰지 않는다)",
        description=(
            "창 안 파워 라이딩 수 구간(8~12, 13~20, 21~30, 31 이상)마다 train 단위의 잔차 "
            "L_FTP − R1로 split conformal 80% 구간을 만들고, dev 단위의 포함률을 선수 단위 "
            "부트스트랩 구간과 함께 잰다(H14, dev 참고 판정). 규칙은 docs/experiments.md 3단계 "
            "'R1 조건부 80% 예측 구간'. 분할을 고르는 옵션은 일부러 두지 않았다."
        ),
    )
    s.add_argument(
        "--units",
        type=Path,
        required=True,
        help="build가 만든 예측 단위 parquet(data/features/units.parquet)",
    )
    s.add_argument(
        "--out",
        type=Path,
        required=True,
        help=(
            "확장자를 뺀 출력 경로(.json과 .md를 쓴다). 3단계 보고서를 덮어쓰지 않게 이름이 -intervals로 "
            "끝나야 한다(예: ../reports/ftp/r1-intervals)"
        ),
    )
    s = sub.add_parser(
        "baseline",
        help="라벨 지속성 기준선 P0를 dev 단위에서 R1과 함께 보고한다(판정 없음, 봉인 test는 거부한다)",
        description=(
            "P0 = 같은 선수의 단위 중 기준일이 28일 이상 앞선 가장 최근 단위의 L_FTP(선수가 전에 설정해 "
            "둔 값). 20분 MMP가 있는 dev 단위에서 P0와 R1의 MAE·중앙값 절대 오차·편향, 짝지은 차이 "
            "P0 − R1, 신선/묵은 라벨별 MAE, L_FTP가 P0와 다른 단위의 비율을 선수 단위 부트스트랩 구간과 "
            "함께 쓴다. 판정은 없다. 규칙은 docs/experiments.md 3단계 '사후 보고: 라벨 지속성 기준선 P0'. "
            "P0는 3단계 후보에 넣지 않으므로 evaluate의 결과는 바뀌지 않는다."
        ),
    )
    s.add_argument(
        "--units",
        type=Path,
        required=True,
        help="build가 만든 예측 단위 parquet(data/features/units.parquet)",
    )
    s.add_argument(
        "--split",
        choices=list(PERSISTENCE_SPLITS),
        required=True,
        help="평가할 분할. dev만 받는다(봉인 test는 3단계에서 한 번 썼으므로 거부한다)",
    )
    s.add_argument(
        "--out",
        type=Path,
        required=True,
        help="확장자를 뺀 출력 경로. 이름이 -persistence로 끝나야 한다(예: ../reports/ftp/dev-persistence)",
    )
    a = p.parse_args(argv)
    if a.cmd == "build":
        cmd_build(a.features, a.index, a.sample, a.out)
    elif a.cmd == "baseline":
        # 3단계 기록(reports/ftp/dev.*·test.* 등)을 덮어쓰지 않게, --out 이름이 -persistence로 끝나지 않으면
        # 단위 파일을 읽기 전에 거부한다(p.error는 사용법과 오류를 stderr에 쓰고 종료 코드 2로 끝낸다).
        # 봉인 test는 --split의 choices가 먼저 막는다.
        if not a.out.name.endswith(PERSISTENCE_OUT_SUFFIX):
            p.error(
                f"baseline: --out must end in {PERSISTENCE_OUT_SUFFIX} (got {a.out.name!r}) "
                "so that the stage 3 reports are not overwritten"
            )
        r = persistence_baseline(pd.read_parquet(a.units), a.split)
        a.out.parent.mkdir(parents=True, exist_ok=True)
        # evaluate·calibrate와 같이 줄 끝은 LF, 한국어 문자열은 그대로(ensure_ascii=False) 쓴다.
        Path(f"{a.out}.json").write_text(
            json.dumps(r, indent=1, ensure_ascii=False, default=str), encoding="utf-8", newline="\n"
        )
        Path(f"{a.out}.md").write_text(persistence_to_markdown(r), encoding="utf-8", newline="\n")
        print(f"wrote {a.out}.json and {a.out}.md (no verdict)")
    elif a.cmd == "calibrate":
        # baseline과 같은 방식으로, --out 이름이 -intervals로 끝나지 않으면 단위 파일을 읽기 전에 거부한다
        # (2026-10-30 추가). 그 전에는 `--out ../reports/ftp/dev`(또는 test)처럼 잘못 준 경로에 커밋된 3단계
        # dev·봉인 test 보고서를 말없이 덮어쓸 수 있었다(git이 추적해 되돌릴 수는 있었지만 측정 기록을 지키는
        # 방식이 하위 명령마다 달랐다). p.error는 사용법과 오류를 stderr에 쓰고 종료 코드 2로 끝낸다.
        if not a.out.name.endswith(INTERVALS_OUT_SUFFIX):
            p.error(
                f"calibrate: --out must end in {INTERVALS_OUT_SUFFIX} (got {a.out.name!r}) "
                "so that the stage 3 reports are not overwritten"
            )
        r = calibrate(pd.read_parquet(a.units))
        a.out.parent.mkdir(parents=True, exist_ok=True)
        # evaluate와 같이 줄 끝은 LF, 한국어 판정 문자열은 그대로(ensure_ascii=False) 쓴다.
        Path(f"{a.out}.json").write_text(
            json.dumps(r, indent=1, ensure_ascii=False, default=str), encoding="utf-8", newline="\n"
        )
        Path(f"{a.out}.md").write_text(calibration_to_markdown(r), encoding="utf-8", newline="\n")
        print(f"wrote {a.out}.json and {a.out}.md: H14 {r['H14']['verdict']}")
    else:
        # 봉인 test는 dev에서 채택 후보를 먼저 정한 뒤 한 번만 재야 하므로, --adopt 없이 test를 돌리는 것을
        # 막는다. test 결과를 보고 채택 후보를 고르는 일을 도구 수준에서 차단하려는 장치다.
        if a.split == "test" and not a.adopt:
            raise SystemExit("--adopt is required for the sealed test run (choose it on dev first)")
        units = pd.read_parquet(a.units)
        r = evaluate(units, a.split, a.adopt)
        a.out.parent.mkdir(parents=True, exist_ok=True)
        # 보고서의 줄 끝은 OS와 무관하게 LF로 쓴다(newline="\n"). 지정하지 않으면 Windows에서 CRLF가
        # 되어, 같은 결과를 Linux에서 다시 만들면 수치가 같아도 파일 전체가 바뀐 것으로 보인다
        # (reports/ftp/의 기존 보고서가 그렇게 CRLF로 커밋돼 있었다). 내용과 수치는 그대로다.
        Path(f"{a.out}.json").write_text(
            json.dumps(r, indent=1, ensure_ascii=False, default=str), encoding="utf-8", newline="\n"
        )
        Path(f"{a.out}.md").write_text(to_markdown(r), encoding="utf-8", newline="\n")
        print(f"wrote {a.out}.json and {a.out}.md")


if __name__ == "__main__":
    main()
