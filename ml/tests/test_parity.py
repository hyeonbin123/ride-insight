# [테스트 묶음] parity.py(1단계 지표 정확성 판정)의 단위 테스트. Kotlin parity 도구가 쓰는 JSONL과 같은 모양의
# 합성 라이딩으로 비교 규칙(GC 임계값으로 TSS 재계산, 정수 TSS 제외, GC 0 = 없음)과 가설 판정(지지·기각)의
# 방향을 확인한다.
# [왜 중요한가] 판정 코드가 틀리면 봉인 세트 결과(H1~H4)를 그대로 믿을 수 없다. 규칙 변경 2·3처럼 측정 중에
# 바뀐 비교 규칙이 코드에 정확히 반영됐는지를 여기서 고정한다.
# MMP_KEYS 상수는 현재 어느 테스트에서도 쓰지 않는다.

import json
from pathlib import Path

import pandas as pd

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
