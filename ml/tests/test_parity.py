# [테스트 묶음] parity.py(1단계 지표 정확성 판정)의 단위 테스트. Kotlin parity 도구가 쓰는 JSONL과 같은 모양의
# 합성 라이딩으로 비교 규칙(GC 임계값으로 TSS 재계산, 정수 TSS 제외, GC 0 = 없음)과 가설 판정(지지·기각)의
# 방향을 확인한다.
# [왜 중요한가] 판정 코드가 틀리면 봉인 세트 결과(H1~H4)를 그대로 믿을 수 없다. 규칙 변경 2·3처럼 측정 중에
# 바뀐 비교 규칙이 코드에 정확히 반영됐는지를 여기서 고정한다.
# 파일 끝의 묶음은 선수 군집 부트스트랩 사후 보고(판정 없음, 규칙 변경 16)를 본다: 군집 구간이 선수에 몰린
# 불일치를 반영하는지, 고르게 흩어진 불일치에서는 라이딩 단위 구간과 비슷한지, 보고 모드가 등록된 분석과 기존
# 보고서 파일을 바꾸지 않는지.
# 그 뒤의 마지막 묶음은 GoldenCheetah 암묵 기록 간격 진단(판정 없음, 규칙 변경 19)을 본다: TSS 정의를 거꾸로
# 푼 암묵 간격, 분류의 순서와 0.5% 경계, 분류 합이 등록된 분석의 그룹과 맞는지, 진단 모드가 기존 보고서를
# 바꾸지 않고 봉인 JSONL을 거부하는지.
# MMP_KEYS 상수는 현재 어느 테스트에서도 쓰지 않는다.

import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from ride_insight_ml import parity

MMP_KEYS = ["1s_critical_power", "20m_critical_power"]


# 합성 라이딩 한 줄. 기본값은 '모든 지표가 GC와 정확히 같은' 1시간 라이딩(cp_setting 250 W, NP로 IF 계산, MMP
# 2구간)이고, 인자로 우리 값과 GC 값을 따로 바꿔 불일치를 만든다.
def _line(
    athlete, i, rec_int, np_ours, np_gc, tss_ours=64.0, tss_gc=64.0, mmp_gc_20m=250.0, mmp_ours_20m=250.0
):
    return {
        "athlete": athlete,
        "date": f"2020/01/{i + 1:02d} 10:00:00 UTC",
        "csv": f"2020_01_{i + 1:02d}_10_00_00.csv",
        "rec_int": rec_int,
        "rec_int_share": 1.0,
        "samples": 3600,
        "last_secs": 3599.0,
        "power_missing": 0,
        "bad_rows": 0,
        "over_3000": 0,
        "negative": 0,
        "cp": 250.0,
        "ours": {
            "np": np_ours,
            "if": np_ours / 250.0,
            "tss": tss_ours,
            "mmp": {"1s_critical_power": 500.0, "20m_critical_power": mmp_ours_20m},
        },
        "gc": {
            "np": np_gc,
            "if": np_gc / 250.0,
            "tss": tss_gc,
            "average_power": 180.0,
            "workout_time": 3600.0,
            "mmp": {"1s_critical_power": 500.0, "20m_critical_power": mmp_gc_20m},
        },
    }


# GC가 IF·TSS에 cp_setting(250)이 아니라 다른 임계값(235)을 쓴 라이딩에서도, NP / IF로 역산한 임계값으로 TSS를
# 다시 계산하면 일치해야 한다(규칙 변경 2). 역산 값 235, cp_differs 표시, 'CP ≠ 임계값' 집계도 함께 본다. 표본
# 밖 선수의 TSS 불일치 92건 중 88건이 바로 이 경우였다.
def test_tss_uses_gc_threshold_not_cp_setting(tmp_path: Path):
    # cp_setting은 250인데 GC는 FTP 235를 썼다(if = np / 235). 그래도 TSS는 일치해야 한다.
    line = _line("a", 0, 1.0, 200.0, 200.0, tss_ours=64.0, tss_gc=100.0 * (200.0 / 235.0) ** 2)
    line["gc"]["if"] = 200.0 / 235.0
    jsonl = tmp_path / "t.jsonl"
    jsonl.write_text(json.dumps(line), encoding="utf-8")
    df = parity.load(jsonl)
    assert abs(df["thr_gc"].iloc[0] - 235.0) < 1e-9
    assert bool(df["cp_differs"].iloc[0])
    r = parity.analyse(df)
    assert r["H3_tss_1s"]["rate"] == 1.0
    assert r["cp_differs_from_gc_threshold"] == {"n": 1, "differs": 1}


# GC TSS가 정확히 정수(50.0)인 라이딩은 수동 입력으로 보고 H3 대상에서 빠지는지(n = 5), 그 수가 보고 항목에
# 세어지는지 확인한다(규칙 변경 3).
def test_integer_gc_tss_is_excluded_from_h3(tmp_path: Path):
    lines = [_line("a", i, 1.0, 201.0, 201.0, tss_gc=64.6416) for i in range(5)]
    lines.append(_line("a", 6, 1.0, 201.0, 201.0, tss_gc=50.0))  # 수동 입력 값: 계산값은 64.64
    jsonl = tmp_path / "t.jsonl"
    jsonl.write_text("\n".join(json.dumps(x) for x in lines), encoding="utf-8")
    r = parity.analyse(parity.load(jsonl))
    assert r["rides_with_integer_gc_tss"] == 1
    assert r["H3_tss_1s"]["n"] == 5
    assert r["H3_tss_1s"]["rate"] == 1.0


# agree의 '없음' 규칙: 둘 다 없음 → 일치, GC 0은 없음으로 보아 우리 쪽도 없으면 일치, 0.5 W 차이는 일치, 2 W
# 차이는 불일치, 우리 값만 있고 GC 값이 없으면 불일치.
def test_agree_treats_absent_and_gc_zero_as_absent():
    ours = pd.Series([None, None, 100.0, 100.0, 100.0])
    theirs = pd.Series([None, 0.0, 100.5, 102.0, None])
    assert parity.agree(ours, theirs, 1.0).tolist() == [True, True, True, False, False]


# 우리 쪽의 0도 '없음'이다(규칙 변경 9). 두 구현이 모두 3000 W 가드에 걸려 둘 다 0이면 일치, 우리 0과 GC
# 없음도 일치, 한쪽만 0이고 다른 쪽에 값이 있으면 불일치. 처음 구현은 첫 두 경우를 불일치로 셌다.
def test_agree_treats_our_zero_as_absent_too():
    ours = pd.Series([0.0, 0.0, None, 0.0, 250.0])
    theirs = pd.Series([0.0, None, 0.0, 250.0, 0.0])
    assert parity.agree(ours, theirs, 1.0).tolist() == [True, True, True, False, False]


# 분석 전체에서도 같아야 한다: 1초 MMP가 양쪽 모두 3000 W 가드로 0인 라이딩은 H2의 일치 쌍으로 센다.
def test_both_sides_guarded_mmp_counts_as_agreement(tmp_path: Path):
    line = _line("a", 0, 1.0, 201.0, 201.0, tss_gc=64.6416)
    line["ours"]["mmp"]["1s_critical_power"] = 0.0
    line["gc"]["mmp"]["1s_critical_power"] = 0.0
    jsonl = tmp_path / "t.jsonl"
    jsonl.write_text(json.dumps(line), encoding="utf-8")
    r = parity.analyse(parity.load(jsonl))
    assert r["H2_mmp_1s"]["pair_rate"] == 1.0
    assert r["mmp_rate_by_window"]["1s_critical_power"] == 1.0


# 전체 판정 흐름: 1초 라이딩 300개가 모두 일치하면 H1·H2·H3 지지, 5초 라이딩 100개 중 40%가 3 W 어긋나면 그 외
# 그룹 일치율 0.6이고 1초와의 차이 40%p로 H4 지지여야 한다. Markdown 보고서에 결과가 들어가는지도 본다.
def test_analysis_verdicts_on_synthetic_data(tmp_path: Path):
    lines = []
    # 정확히 일치하는 1초 라이딩 300개 -> H1/H2/H3 지지.
    for i in range(300):
        lines.append(_line(f"a{i % 10}", i % 28, 1.0, 201.0, 201.0, tss_ours=64.6416, tss_gc=64.6416))
    # 5초 라이딩 100개 중 40%가 3 W 어긋남 -> H4(1초 일치율이 5%p 이상 높음) 지지.
    for i in range(100):
        off = 3.0 if i % 5 < 2 else 0.0
        lines.append(_line(f"b{i % 5}", i % 28, 5.0, 201.0 + off, 201.0, tss_ours=64.6416, tss_gc=64.6416))
    jsonl = tmp_path / "dev.jsonl"
    jsonl.write_text("\n".join(json.dumps(x) for x in lines), encoding="utf-8")
    df = parity.load(jsonl)
    r = parity.analyse(df)
    assert r["rides"] == 400
    assert r["interval_groups"] == {"1s": 300, "5s": 100}
    assert r["H1_np_1s"]["verdict"] == "지지"
    assert r["H2_mmp_1s"]["verdict"] == "지지"
    assert r["H3_tss_1s"]["verdict"] == "지지"
    assert r["H4_np_other"]["rate"] == 0.6
    assert r["H4_np_other"]["verdict"] == "지지"
    md = parity.to_markdown(r, "dev")
    assert "H1 NP" in md and "지지" in md


# 반대 방향 검사: 절반이 2 W 어긋나 일치율이 50%면 99% 기준에 확실히 못 미치므로 H1이 '기각'이어야 한다. 판정
# 함수가 늘 '지지'만 내는 실수를 막는다.
def test_rejection_when_rate_is_clearly_low():
    lines = [
        _line("a", i % 28, 1.0, 201.0 + (2.0 if i % 2 else 0.0), 201.0, tss_gc=64.6416) for i in range(200)
    ]
    df = parity.load(_write(lines))
    r = parity.analyse(df)
    assert r["H1_np_1s"]["rate"] == 0.5
    assert r["H1_np_1s"]["verdict"] == "기각"


# tmp_path 없이 JSONL을 쓰는 도우미. pytest 설정의 --basetemp(.pytest-tmp)가 있으면 그 아래에 임시 폴더를
# 만든다(기본 %TEMP% 폴더 권한 문제를 피하려는 프로젝트 설정, ml/pyproject.toml 주석).
def _write(lines):
    import tempfile

    p = Path(tempfile.mkdtemp(dir=".pytest-tmp" if Path(".pytest-tmp").exists() else None)) / "x.jsonl"
    p.write_text("\n".join(json.dumps(x) for x in lines), encoding="utf-8")
    return p


# ---- 선수 군집 부트스트랩 사후 보고(판정 없음, 규칙 변경 16) ----
# 규칙: docs/experiments.md 1단계 '사후 보고: 선수 군집 부트스트랩'.


# 선수 2명 중 한 명은 모든 라이딩이 일치하고 다른 한 명은 모두 어긋나면, 선수를 복원 추출할 때 같은 선수만 두
# 번 뽑히는 경우가 각각 1/4이라 2.5%·97.5% 분위수가 0과 1에 놓인다. 즉 선수 군집 구간은 [0, 1] 양 끝에 닿아야
# 한다. 같은 마스크의 라이딩 단위 구간은 라이딩 100개를 독립으로 보므로 0.5 근처에 좁게 머문다(군집을 무시하면
# 구간이 얼마나 좁아지는지 보여 주는 극단적인 경우).
def test_cluster_interval_reaches_both_ends_with_two_athletes():
    mask = pd.Series([True] * 50 + [False] * 50)
    athletes = pd.Series(["good"] * 50 + ["bad"] * 50)
    cluster = parity.cluster_bootstrap_rate(mask, athletes)
    assert cluster["n"] == 100 and cluster["athletes"] == 2
    assert cluster["rate"] == 0.5
    assert cluster["ci"] == [0.0, 1.0]
    ride = parity.bootstrap_rate(mask)
    assert 0.35 < ride["ci"][0] < 0.5 < ride["ci"][1] < 0.65


# 불일치가 선수와 상관없이 고르게 흩어져 있으면(선수 100명 × 라이딩 30개, 불일치 확률 10%를 seed 고정 난수로
# 배정) 선수 군집 구간의 폭은 라이딩 단위 구간과 비슷해야 한다. 군집 효과가 없는 데이터에서 군집 부트스트랩이
# 구간을 공연히 넓히거나 좁히지 않는다는 확인이다. 점 추정(일치율)은 두 방법이 같다.
def test_cluster_interval_is_close_to_ride_interval_when_mismatches_are_spread():
    rng = np.random.default_rng(1)
    athletes = pd.Series([f"a{i % 100:03d}" for i in range(3000)])
    mask = pd.Series(rng.random(3000) >= 0.1)
    ride = parity.bootstrap_rate(mask)
    cluster = parity.cluster_bootstrap_rate(mask, athletes)
    assert cluster["athletes"] == 100
    assert cluster["rate"] == ride["rate"]
    ratio = (cluster["ci"][1] - cluster["ci"][0]) / (ride["ci"][1] - ride["ci"][0])
    assert 0.75 < ratio < 1.33


# 선수 군집 구간은 선수별 일치율의 평균이 아니라 뽑힌 선수의 라이딩을 합쳐 센다(규칙의 '일치 수 합 / 라이딩 수
# 합'). 라이딩 1,000개가 모두 일치하는 선수 한 명과 라이딩 1개가 어긋나는 선수 19명이면, 큰 선수가 두세 번
# 뽑힌 재표집의 비율이 합으로는 99% 넘게(3,000/3,017 등), 선수별 평균으로는 15% 이하(3/20)라 상한이 크게
# 갈린다. 큰 선수가 한 번도 안 뽑힐 확률은 약 36%라 하한은 0이다. 라이딩 단위로 뽑으면 하한이 0이 될 수 없다.
def test_cluster_interval_pools_the_rides_of_drawn_athletes():
    mask = pd.Series([True] * 1000 + [False] * 19)
    athletes = pd.Series(["big"] * 1000 + [f"s{i:02d}" for i in range(19)])
    cluster = parity.cluster_bootstrap_rate(mask, athletes)
    assert cluster["rate"] == parity.bootstrap_rate(mask)["rate"] == 1000 / 1019
    assert cluster["ci"][0] == 0.0
    assert cluster["ci"][1] > 0.99


# 보고 전용 비교 함수(규칙 변경 9 전, 봉인 측정 때의 agree)는 우리 쪽의 결측만 '없음'으로 본다. 그래서 둘 다
# 0인 쌍과 '우리 0·GC 없음'인 쌍은 불일치이고, 우리 0과 GC 0.5 W는 둘 다 값으로 보아 차이 1 W 이하라 일치다.
# 현재 함수는 이 세 쌍을 반대로 센다(docs/experiments.md '사후 수정 재측정 계획'의 '바뀐 것').
def test_agree_before_rule9_counts_our_zero_as_a_value():
    ours = pd.Series([0.0, 0.0, None, 0.0, 250.0, 0.0])
    theirs = pd.Series([0.0, None, 0.0, 250.0, 0.0, 0.5])
    assert parity.agree_before_rule9(ours, theirs, 1.0).tolist() == [False, False, True, False, False, True]
    assert parity.agree(ours, theirs, 1.0).tolist() == [True, True, True, False, False, False]


# 군집 보고용 합성 세트. 'good' 선수는 1초 라이딩 40개가 모두 일치하고, 'bad' 선수는 1초 라이딩 10개 모두 우리
# NP가 2 W 높고(H1 불일치, 그 NP로 다시 계산한 TSS도 0.5 넘게 어긋나 H3 불일치) 20분 MMP가 3 W 높다(H2는 2구간
# 중 1구간 불일치). 1초가 아닌 5초 라이딩은 good 3개(일치)와 bad 1개(NP 불일치).
def _two_athlete_lines():
    lines = [_line("good", i, 1.0, 201.0, 201.0, tss_gc=64.6416) for i in range(40)]
    lines += [_line("bad", i, 1.0, 203.0, 201.0, tss_gc=64.6416, mmp_ours_20m=253.0) for i in range(10)]
    lines += [_line("good", 40 + i, 5.0, 201.0, 201.0, tss_gc=64.6416) for i in range(3)]
    lines.append(_line("bad", 10, 5.0, 203.0, 201.0, tss_gc=64.6416))
    return lines


def _write_jsonl(path: Path, lines) -> Path:
    path.write_text("\n".join(json.dumps(x) for x in lines), encoding="utf-8")
    return path


# 선수 2명 합성 세트의 군집 보고: 선수 군집 구간이 양 끝에 닿고(H1·H3 [0, 1]), 불일치가 모두 한 선수에 있으며
# (비율 1.0), 그 선수를 빼면 일치율 1.0이고, H4 그 외 그룹은 구간 대신 선수별 표(라이딩 수가 많은 순서)가
# 나온다. 이 세트에는 0 값이 없어 두 비교 함수의 결과가 같아야 한다.
def test_cluster_report_on_two_athletes(tmp_path: Path):
    report = parity.cluster_report(parity.load(_write_jsonl(tmp_path / "t.jsonl", _two_athlete_lines())))
    assert report["report"] == "athlete_cluster_bootstrap" and report["verdict"] is None
    assert (report["rides"], report["athletes"]) == (54, 2)
    current = report["comparisons"]["current"]
    h1 = current["H1_np_1s"]
    assert (h1["rides"], h1["athletes"], h1["rate"], h1["cluster_ci"]) == (50, 2, 0.8, [0.0, 1.0])
    assert h1["ride_ci"][1] - h1["ride_ci"][0] < 0.3
    h2 = current["H2_mmp_1s"]
    assert (h2["rides"], h2["pairs"], h2["rate"]) == (50, 100, 0.9)
    h3 = current["H3_tss_1s"]
    assert (h3["rides"], h3["rate"], h3["cluster_ci"]) == (50, 0.8, [0.0, 1.0])
    concentration = current["mismatch_concentration"]
    assert concentration["H1_np_1s"] == {
        "total": 10,
        "top": [{"athlete": "bad", "mismatches": 10, "share": 1.0, "rides": 10, "rate": 0.0}],
    }
    assert concentration["H2_mmp_1s"]["total"] == 10
    assert concentration["H2_mmp_1s"]["top"][0]["rate"] == 0.5
    without = current["without_top_athlete"]["H1_np_1s"]
    assert without == {"excluded": "bad", "rides": 40, "athletes": 1, "rate": 1.0, "cluster_ci": [1.0, 1.0]}
    h4 = current["H4_np_other"]
    assert (h4["rides"], h4["athletes"], h4["rate"]) == (4, 2, 0.75)
    assert "cluster_ci" not in h4
    assert h4["per_athlete"] == [
        {"athlete": "good", "rides": 3, "agree": 3, "rate": 1.0},
        {"athlete": "bad", "rides": 1, "agree": 0, "rate": 0.0},
    ]
    assert report["comparisons"]["before_rule9"] == current


# 두 비교 함수를 모두 낸다: 1초 MMP가 양쪽 모두 3000 W 가드로 0인 라이딩 하나는 현재 함수로는 일치, 봉인 측정
# 때의 함수로는 불일치 쌍 1개다(규칙 변경 9). 봉인 표와 맞출 값은 before_rule9 쪽이다. 불일치가 없으면 뺄
# 선수도 없다(excluded None).
def test_cluster_report_keeps_the_sealed_run_comparison_function(tmp_path: Path):
    guarded = _line("a", 0, 1.0, 201.0, 201.0, tss_gc=64.6416)
    guarded["ours"]["mmp"]["1s_critical_power"] = 0.0
    guarded["gc"]["mmp"]["1s_critical_power"] = 0.0
    lines = [guarded] + [_line("a", i, 1.0, 201.0, 201.0, tss_gc=64.6416) for i in range(1, 4)]
    report = parity.cluster_report(parity.load(_write_jsonl(tmp_path / "t.jsonl", lines)))
    current = report["comparisons"]["current"]
    assert current["H2_mmp_1s"]["rate"] == 1.0
    assert current["mismatch_concentration"]["H2_mmp_1s"] == {"total": 0, "top": []}
    assert current["without_top_athlete"]["H2_mmp_1s"] == {"excluded": None}
    before = report["comparisons"]["before_rule9"]
    assert before["H2_mmp_1s"]["rate"] == 7 / 8
    assert before["mismatch_concentration"]["H2_mmp_1s"]["total"] == 1


# --cluster-report 실행은 <out>.json·<out>.md 두 파일만 새로 만들고, 같은 폴더의 기존 분석
# 보고서(sealed.json)와 JSONL을 바꾸지 않으며 기존 분석 보고서(sealed.md)를 새로 만들지도 않는다(봉인 기록을
# 덮어쓰지 않는다).
def test_cluster_report_cli_writes_only_the_cluster_files(tmp_path: Path):
    jsonl = _write_jsonl(tmp_path / "sealed.jsonl", _two_athlete_lines())
    (tmp_path / "sealed.json").write_text("recorded", encoding="utf-8")
    before = {p.name: p.read_bytes() for p in tmp_path.iterdir()}
    parity.main(["--jsonl", str(jsonl), "--out", str(tmp_path / "sealed-cluster"), "--cluster-report"])
    after = {p.name: p.read_bytes() for p in tmp_path.iterdir()}
    assert set(after) - set(before) == {"sealed-cluster.json", "sealed-cluster.md"}
    assert {name: after[name] for name in before} == before
    report = json.loads(after["sealed-cluster.json"].decode("utf-8"))
    assert report["comparisons"]["current"]["H1_np_1s"]["cluster_ci"] == [0.0, 1.0]
    md = after["sealed-cluster.md"].decode("utf-8")
    assert "판정 없음" in md and "선수 군집" in md and "H4" in md
    assert "\r\n" not in md


# --out 이름이 -cluster로 끝나지 않으면 군집 보고 모드는 아무것도 쓰지 않고 끝난다. 실수로 '--out
# .../sealed'를 주면 봉인 기록(sealed.json/md)을 덮어쓰게 되기 때문이다. 오류 문장까지 보는 것은, 옵션을
# 모르는 옛 코드도 argparse 오류(SystemExit)로 끝나서 종료만 보면 이 거부를 확인할 수 없기 때문이다.
def test_cluster_report_cli_refuses_an_out_name_without_the_cluster_suffix(tmp_path: Path, capsys):
    jsonl = _write_jsonl(tmp_path / "sealed.jsonl", _two_athlete_lines())
    (tmp_path / "sealed.json").write_text("recorded", encoding="utf-8")
    with pytest.raises(SystemExit):
        parity.main(["--jsonl", str(jsonl), "--out", str(tmp_path / "sealed"), "--cluster-report"])
    assert "ending in -cluster" in capsys.readouterr().err
    assert (tmp_path / "sealed.json").read_text(encoding="utf-8") == "recorded"
    assert sorted(p.name for p in tmp_path.iterdir()) == ["sealed.json", "sealed.jsonl"]


# 군집 보고를 만들어도 등록된 분석(analyse)의 H1~H4 수치·판정은 같다: 보고가 입력 표를 바꾸지 않고, 보고의
# 현재 비교 함수 쪽 라이딩 단위 값(n·일치율·구간)이 analyse와 같으며(두 코드의 가설 대상 라이딩 선택이 같다는
# 확인), 옵션 없이 실행한 기본 출력도 analyse 결과 그대로이고 군집 보고 파일을 만들지 않는다.
def test_cluster_report_leaves_the_registered_analysis_unchanged(tmp_path: Path):
    jsonl = _write_jsonl(tmp_path / "dev.jsonl", _two_athlete_lines())
    df = parity.load(jsonl)
    registered = parity.analyse(df)
    report = parity.cluster_report(df)
    assert parity.analyse(df) == registered
    current = report["comparisons"]["current"]
    for key in ("H1_np_1s", "H2_mmp_1s", "H3_tss_1s", "H4_np_other"):
        assert current[key]["rides"] == registered[key]["n"]
        assert current[key]["rate"] == registered[key]["rate"]
        assert current[key]["ride_ci"] == registered[key]["ci"]
    parity.main(["--jsonl", str(jsonl), "--out", str(tmp_path / "dev")])
    written = json.loads((tmp_path / "dev.json").read_text(encoding="utf-8"))
    assert written == json.loads(json.dumps(registered, ensure_ascii=False, default=str))
    assert not (tmp_path / "dev-cluster.json").exists()


# ---- GoldenCheetah 암묵 기록 간격 진단(판정 없음, 규칙 변경 19) ----
# 규칙: docs/experiments.md 1단계 '사후 보고: GoldenCheetah 암묵 기록 간격'. GC의 TSS 정의(샘플 수 ×
# recIntSecs × IF² / 36)를 거꾸로 풀어 GC가 쓴 기록 간격을 구하고, 우리 최빈 간격과 비교해 라이딩을 나눈다.


# 암묵 간격 진단용 합성 라이딩. GC가 기록 간격 gc_rec(초)로 계산했다고 보고 GC TSS를 TSS 정의대로 만든다(샘플
# 1,800개, IF = GC NP / 250). rec_int는 우리가 CSV에서 추정한 최빈 간격, share는 그 최빈 간격의 비율이다. 기본
# NP 201 W면 IF² = 0.646416이라 TSS가 정수가 되지 않는다(정수 TSS는 수동 입력으로 보아 암묵 간격을 내지 않음).
def _recint_line(
    athlete, i, rec_int, gc_rec, share=1.0, np_ours=201.0, np_gc=201.0, mmp_ours_20m=250.0, mmp_gc_20m=250.0
):
    line = _line(athlete, i, rec_int, np_ours, np_gc, mmp_gc_20m=mmp_gc_20m, mmp_ours_20m=mmp_ours_20m)
    line["samples"] = 1800
    line["rec_int_share"] = share
    line["gc"]["tss"] = 1800 * gc_rec * (np_gc / 250.0) ** 2 / 36.0
    return line


# 암묵 간격은 TSS 정의를 거꾸로 푼 값이다: GC가 2초 파일을 1초로 계산했으면 1.0, 5초 파일을 5초로 계산했으면
# 5.0이다. GC TSS가 정수(수동 입력)이거나, GC IF가 0이거나, GC TSS가 없으면 계산하지 않는다(NaN).
def test_gc_implied_interval_inverts_the_tss_definition(tmp_path: Path):
    integer = _recint_line("a", 2, 1.0, 1.0)
    integer["gc"]["tss"] = 32.0
    zero_if = _recint_line("a", 3, 1.0, 1.0)
    zero_if["gc"]["if"] = 0.0
    no_tss = _recint_line("a", 4, 1.0, 1.0)
    no_tss["gc"]["tss"] = None
    lines = [_recint_line("a", 0, 2.0, 1.0), _recint_line("a", 1, 5.0, 5.0), integer, zero_if, no_tss]
    implied = parity.gc_implied_interval(parity.load(_write_jsonl(tmp_path / "dev.jsonl", lines)))
    assert implied.iloc[0] == pytest.approx(1.0)
    assert implied.iloc[1] == pytest.approx(5.0)
    assert implied.iloc[2:].isna().all()


# 2초 간격 CSV인데 GC TSS·IF가 1초 기준인 라이딩(GC가 2초 파일을 1초로 계산함)은 '비1초·암묵≠최빈'이다. 우리
# NP·20분 MMP가 GC와 어긋난 것은 이 분류의 불일치이고, 최빈 간격이 1초가 아닌 쪽(H4 대상)에 센다. 최빈 간격과
# 암묵 간격 쌍은 (2.0, 1.0)이다. 다른 분류는 비어 있다.
def test_recint_diag_classifies_a_2s_file_that_gc_computed_as_1s(tmp_path: Path):
    line = _recint_line("p", 0, 2.0, 1.0, np_ours=205.0, mmp_ours_20m=240.0)
    r = parity.recint_diag(parity.load(_write_jsonl(tmp_path / "dev.jsonl", [line])))
    assert r["report"] == "gc_implied_recording_interval" and r["verdict"] is None
    c = r["categories"]["non1s_differs"]
    assert (c["rides"], c["athletes"], c["mode_1s"], c["mode_other"], c["implied_same"]) == (1, 1, 0, 1, 0)
    assert c["np"] == {"n": 1, "agree": 0, "rate": 0.0, "mismatch_1s": 0, "mismatch_other": 1}
    assert c["mmp_20m"] == {
        "n": 1,
        "agree": 0,
        "rate": 0.0,
        "mismatch_1s": 0,
        "mismatch_other": 1,
        "both_absent": 0,
    }
    assert c["pairs"] == [{"mode": 2.0, "implied": 1.0, "rides": 1}]
    others = [k for k in parity.RECINT_CATEGORIES if k != "non1s_differs"]
    assert all(r["categories"][k]["rides"] == 0 for k in others)


# GC TSS가 정수(수동 입력)인 라이딩, GC IF가 0인 라이딩, 최빈 간격이 없는 라이딩(샘플 1개)은 암묵 간격을 최빈
# 간격과 비교할 수 없어 '암묵 없음'이다. 이유는 'GC TSS나 IF가 없거나 0 이하 → 정수 TSS → 샘플이나 최빈 간격
# 없음' 순서로 처음 맞는 하나로 센다. 정수 TSS 라이딩은 최빈 간격이 1초라 H1 대상(mode_1s)이기도 하다.
def test_recint_diag_puts_integer_tss_and_zero_if_in_no_implied(tmp_path: Path):
    integer = _recint_line("a", 0, 1.0, 1.0)
    integer["gc"]["tss"] = 50.0
    zero_if = _recint_line("a", 1, 2.0, 1.0)
    zero_if["gc"]["if"] = 0.0
    no_interval = _recint_line("b", 2, None, 1.0)
    no_interval["samples"] = 1
    no_interval["rec_int_share"] = None
    r = parity.recint_diag(parity.load(_write_jsonl(tmp_path / "dev.jsonl", [integer, zero_if, no_interval])))
    c = r["categories"]["no_implied"]
    assert (c["rides"], c["athletes"], c["mode_1s"], c["mode_other"]) == (3, 2, 1, 2)
    assert c["reasons"] == {"gc_tss_or_if_absent": 1, "gc_tss_integer": 1, "no_samples_or_interval": 1}
    assert "pairs" not in c and "implied_same" not in c
    assert sum(r["categories"][k]["rides"] for k in parity.RECINT_CATEGORIES) == 3


# 흔들림(최빈 간격 비율 < 0.9)은 최빈 간격이 몇 초든 먼저 나눈다: 최빈 1.00·비율 0.8인 파일을 GC가 1.03초로
# 계산했으면 '흔들림·암묵≠최빈'이고 H1 대상(mode_1s)으로 센다. 최빈 1.26·비율 0.7이고 GC도 1.26초면
# '흔들림·암묵=최빈'. 흔들림이 아니면 최빈 1.00은 '1초'다(암묵 1.004는 0.5% 안이라 implied_same에 세고, GC가
# 2초로 계산한 1초 파일은 세지 않는다). 5초는 0.5% 경계 안팎(5.02는 같음, 5.03은 다름)에서 '비1초·암묵=최빈'과
# '비1초·암묵≠최빈'으로 갈린다.
def test_recint_diag_separates_jitter_and_uses_the_half_percent_tolerance(tmp_path: Path):
    lines = [
        _recint_line("j1", 0, 1.0, 1.03, share=0.8),
        _recint_line("j2", 1, 1.26, 1.26, share=0.7),
        _recint_line("s1", 2, 1.0, 1.0),
        _recint_line("s1", 3, 1.0, 1.004),
        _recint_line("s1", 4, 1.0, 2.0),
        _recint_line("f1", 5, 5.0, 5.02),
        _recint_line("f2", 6, 5.0, 5.03),
    ]
    cats = parity.recint_diag(parity.load(_write_jsonl(tmp_path / "dev.jsonl", lines)))["categories"]
    jd, js = cats["jitter_differs"], cats["jitter_same"]
    assert (jd["rides"], jd["mode_1s"], jd["implied_same"]) == (1, 1, 0)
    assert jd["pairs"] == [{"mode": 1.0, "implied": 1.03, "rides": 1}]
    assert (js["rides"], js["mode_other"], js["implied_same"]) == (1, 1, 1)
    assert (cats["1s"]["rides"], cats["1s"]["athletes"], cats["1s"]["implied_same"]) == (3, 1, 2)
    assert cats["1s"]["pairs"] == [
        {"mode": 1.0, "implied": 1.0, "rides": 2},
        {"mode": 1.0, "implied": 2.0, "rides": 1},
    ]
    assert (cats["non1s_same"]["rides"], cats["non1s_differs"]["rides"]) == (1, 1)
    assert cats["non1s_differs"]["pairs"] == [{"mode": 5.0, "implied": 5.03, "rides": 1}]
    assert cats["no_implied"]["rides"] == 0


# 분류 전체의 합은 등록된 분석의 그룹과 맞아야 한다: 최빈 1초 합 = H1 대상 수, 그 외 합 = H4 대상 수, 최빈
# 1초의 NP 불일치 합 = H1 불일치 수, 그 외의 NP 불일치 합 = H4 불일치 수, 최빈 1초의 20분 MMP 일치 수 / H1
# 대상 수 = analyse의 20분 구간 일치율. 진단을 만들어도 입력 표의 열과 analyse 결과는 그대로다.
def test_recint_diag_totals_match_the_registered_analysis(tmp_path: Path):
    integer = _recint_line("d", 6, 1.0, 1.0, np_ours=199.0)
    integer["gc"]["tss"] = 50.0
    lines = [
        _recint_line("a", 0, 1.0, 1.0),
        _recint_line("a", 1, 1.0, 1.0, np_ours=203.0),
        _recint_line("b", 2, 2.0, 1.0, np_ours=205.0, mmp_ours_20m=240.0),
        _recint_line("b", 3, 5.0, 5.0),
        _recint_line("c", 4, 1.0, 1.03, share=0.8, mmp_ours_20m=252.0),
        _recint_line("c", 5, 1.26, 1.26, share=0.7, np_ours=203.0),
        integer,
    ]
    df = parity.load(_write_jsonl(tmp_path / "dev.jsonl", lines))
    columns = list(df.columns)
    registered = parity.analyse(df)
    r = parity.recint_diag(df)
    assert list(df.columns) == columns
    assert parity.analyse(df) == registered
    h1, h4 = registered["H1_np_1s"], registered["H4_np_other"]
    cats = [r["categories"][k] for k in parity.RECINT_CATEGORIES]
    assert sum(c["rides"] for c in cats) == r["rides"] == 7
    assert r["registered_groups"] == {"1s": h1["n"], "other": h4["n"]} == {"1s": 4, "other": 3}
    assert sum(c["mode_1s"] for c in cats) == h1["n"]
    assert sum(c["mode_other"] for c in cats) == h4["n"]
    assert sum(c["np"]["mismatch_1s"] for c in cats) == round(h1["n"] * (1 - h1["rate"])) == 2
    assert sum(c["np"]["mismatch_other"] for c in cats) == round(h4["n"] * (1 - h4["rate"])) == 2
    mmp_1s_agree = sum(c["mode_1s"] - c["mmp_20m"]["mismatch_1s"] for c in cats)
    assert mmp_1s_agree / h1["n"] == registered["mmp_rate_by_window"]["20m_critical_power"] == 0.75


# --recint-diag 실행은 <out>.json·<out>.md 두 파일만 새로 만들고, 같은 폴더의 기존 분석 보고서(dev.json)와
# JSONL을 바꾸지 않으며 기존 분석 보고서(dev.md)를 새로 만들지도 않는다. 보고서 줄 끝은 LF다.
def test_recint_diag_cli_writes_only_the_diag_files(tmp_path: Path):
    lines = [_recint_line("p", 0, 2.0, 1.0, np_ours=205.0), _recint_line("q", 1, 1.0, 1.0)]
    jsonl = _write_jsonl(tmp_path / "dev.jsonl", lines)
    (tmp_path / "dev.json").write_text("recorded", encoding="utf-8")
    before = {p.name: p.read_bytes() for p in tmp_path.iterdir()}
    parity.main(["--jsonl", str(jsonl), "--out", str(tmp_path / "dev-recint-diag"), "--recint-diag"])
    after = {p.name: p.read_bytes() for p in tmp_path.iterdir()}
    assert set(after) - set(before) == {"dev-recint-diag.json", "dev-recint-diag.md"}
    assert {name: after[name] for name in before} == before
    report = json.loads(after["dev-recint-diag.json"].decode("utf-8"))
    assert report["categories"]["non1s_differs"]["rides"] == 1
    assert report["categories"]["1s"]["rides"] == 1
    md = after["dev-recint-diag.md"].decode("utf-8")
    assert "판정 없음" in md and "비1초·암묵≠최빈" in md and "암묵 없음" in md
    assert "\r\n" not in md


# 진단 모드는 --out 이름이 -recint-diag로 끝나지 않을 때(기존 보고서를 덮어쓸 수 있음), --jsonl 파일 이름이
# sealed로 시작할 때(규칙상 봉인 세트는 이 진단에 쓰지 않음), --cluster-report와 함께 줄 때 아무것도 쓰지 않고
# 끝난다. 오류 문장까지 보는 것은 옵션을 모르는 옛 코드도 argparse 오류(SystemExit)로 끝나기 때문이다.
def test_recint_diag_cli_refuses_unsafe_runs(tmp_path: Path, capsys):
    dev = _write_jsonl(tmp_path / "dev.jsonl", [_recint_line("q", 0, 1.0, 1.0)])
    sealed = _write_jsonl(tmp_path / "sealed.jsonl", [_recint_line("q", 0, 1.0, 1.0)])
    (tmp_path / "dev.json").write_text("recorded", encoding="utf-8")
    both_modes = ["--jsonl", str(dev), "--out", str(tmp_path / "dev-recint-diag"), "--recint-diag"]
    cases = [
        (["--jsonl", str(dev), "--out", str(tmp_path / "dev"), "--recint-diag"], "ending in -recint-diag"),
        (
            ["--jsonl", str(sealed), "--out", str(tmp_path / "sealed-recint-diag"), "--recint-diag"],
            "development set only",
        ),
        (both_modes + ["--cluster-report"], "cannot be used together"),
    ]
    for argv, message in cases:
        with pytest.raises(SystemExit):
            parity.main(argv)
        assert message in capsys.readouterr().err
    assert (tmp_path / "dev.json").read_text(encoding="utf-8") == "recorded"
    assert sorted(p.name for p in tmp_path.iterdir()) == ["dev.json", "dev.jsonl", "sealed.jsonl"]
