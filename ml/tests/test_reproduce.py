# [테스트 묶음] reproduce.py(한 명령 재현 확인)의 테스트. 실제 OpenData 없이 합성 입력(라이딩별 JSONL·예측
# 단위 parquet)과, 그 입력으로 기존 명령(parity.py·ftp.py)이 저장소 위치에 쓴 '커밋된' 보고서를 담은 작은
# 저장소 모양 폴더를 만든다. 그 위에서 도구가 (1) 다시 만든 보고서가 같으면 0, (2) 값 하나·Markdown 한 줄·줄
# 끝만 바꿔도 1과 차이 목록, (3) 입력이 없으면 만드는 명령을 안내하고 2를 돌려주는지, 그리고 어느 경우에도
# 커밋된 보고서와 입력을 바꾸지 않고 임시 폴더(또는 --out-dir)에만 쓰는지를 본다.
# [왜 중요한가] 이 도구는 '규칙을 먼저 커밋하고 한 번만 잰' 기록이 같은 입력과 지금 코드로 다시 나오는지를
# 제3자가 한 명령으로 확인하는 장치다. 비교가 느슨하면 바뀐 수치를 놓치고, 결과를 reports/에 쓰면 확인하려던
# 기록을 덮어쓴다.

import datetime as dt
import json
import shutil
import tempfile
from pathlib import Path

import pytest

from ride_insight_ml import ftp, parity, reproduce


# Kotlin parity 도구가 쓰는 라이딩별 JSONL 한 줄(tests/test_parity.py의 _line과 같은 모양). 1시간 라이딩,
# NP·IF·TSS와 MMP 2구간. np_ours가 np_gc와 다르면 그 라이딩은 NP 불일치가 된다.
def _parity_line(athlete: str, i: int, rec_int: float, np_ours: float) -> dict:
    day = dt.datetime(2020, 1, 1, 10) + dt.timedelta(days=i)
    return {
        "athlete": athlete,
        "date": day.strftime("%Y/%m/%d %H:%M:%S") + " UTC",
        "csv": day.strftime("%Y_%m_%d_%H_%M_%S") + ".csv",
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
            "tss": 64.6416,
            "mmp": {"1s_critical_power": 500.0, "20m_critical_power": 250.0},
        },
        "gc": {
            "np": 201.0,
            "if": 201.0 / 250.0,
            "tss": 64.6416,
            "average_power": 180.0,
            "workout_time": 3600.0,
            "mmp": {"1s_critical_power": 500.0, "20m_critical_power": 250.0},
        },
    }


# 선수 2명의 합성 개발 세트 JSONL: 1초 라이딩은 'good'이 모두 일치하고 'bad'가 모두 2 W 어긋나며, 5초 라이딩도
# 몇 개 둔다. 등록된 분석·선수 군집 보고·암묵 기록 간격 진단이 모두 돌 수 있는 최소 모양이다.
def _parity_lines() -> list[dict]:
    lines = [_parity_line("good", i, 1.0, 201.0) for i in range(30)]
    lines += [_parity_line("bad", i, 1.0, 203.0) for i in range(10)]
    lines += [_parity_line("good", 30 + i, 5.0, 201.0) for i in range(4)]
    lines += [_parity_line("bad", 10 + i, 5.0, 204.0) for i in range(2)]
    return lines


# 3단계 build 입력(라이딩 특징 JSONL) 한 줄. tests/test_ftp.py의 _ride와 같은 곡선: MMP가 FTP에 비례하고, GC
# 값은 NP / IF가 정확히 FTP(=L_FTP)가 되게 둔다.
def _ftp_ride(athlete: str, day: int, ftp_w: float) -> dict:
    secs = {"1s": 1, "5s": 5, "10s": 10, "15s": 15, "20s": 20, "30s": 30, "1m": 60, "2m": 120, "3m": 180}
    secs |= {"5m": 300, "8m": 480, "10m": 600, "20m": 1200, "30m": 1800, "60m": 3600, "90m": 5400}
    mmp = {f"{k}_critical_power": ftp_w * (1 + 2.0 / (1 + t / 60)) for k, t in secs.items()}
    return {
        "athlete": athlete,
        "date": (dt.datetime(2020, 1, 1) + dt.timedelta(days=day)).strftime("%Y/%m/%d %H:%M:%S") + " UTC",
        "rec_int": 1.0,
        "samples": 3600,
        "cp": ftp_w + 10,
        "weight": 70.0,
        "ours": {"np": ftp_w * 0.8, "mmp": mmp},
        "gc": {"np": ftp_w * 0.8, "if": 0.8},
    }


# 선수 12명(train 8, dev 2, test 2)의 합성 라이딩으로 ftp build를 돌려 예측 단위 parquet을 out에 쓴다. 3일마다
# 라이딩, 100일째에 FTP가 20 W 오른다(tests/test_ftp.py의 _write_fixture와 같은 모양). train 단위가 모델
# 후보의 최소 학습 수(50)를 넘어 evaluate의 모든 후보가 돈다.
def _build_units(work: Path, out: Path) -> None:
    rows = []
    for i in range(12):
        base = 200 + 10 * i
        rows += [_ftp_ride(f"a{i}", day, base + (20 if day >= 100 else 0)) for day in range(0, 200, 3)]
    features = work / "all.jsonl"
    features.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    index = work / "index"
    index.mkdir()
    for i in range(12):
        (index / f"a{i}.json").write_text(
            json.dumps({"gender": "MF"[i % 2], "yob": "1985"}), encoding="utf-8"
        )
    sample = work / "sample.json"
    splits = ["train"] * 8 + ["dev"] * 2 + ["test"] * 2
    sample.write_text(
        json.dumps([{"id": f"a{i}", "split": s} for i, s in enumerate(splits)]), encoding="utf-8"
    )
    ftp.cmd_build(features, index, sample, out)


# 저장소 모양 폴더 하나를 모듈에 한 번만 만든다: 두 입력(reports/parity/dev.jsonl,
# data/features/units.parquet)과, 그 입력으로 docs/experiments.md에 적힌 기록 명령 그대로 저장소 위치에 쓴
# 보고서 6개(.json·.md 12개). 이것이 테스트에서 '커밋된 보고서' 역할을 한다. reproduce를 쓰지 않고 만들어
# 도구가 자기 출력과 비교하는 순환을 피한다.
@pytest.fixture(scope="module")
def committed_repo(tmp_path_factory) -> Path:
    root = tmp_path_factory.mktemp("repo")
    jsonl = root / "reports" / "parity" / "dev.jsonl"
    jsonl.parent.mkdir(parents=True)
    jsonl.write_text("\n".join(json.dumps(x) for x in _parity_lines()), encoding="utf-8")
    units = root / "data" / "features" / "units.parquet"
    units.parent.mkdir(parents=True)
    _build_units(tmp_path_factory.mktemp("build"), units)
    rp, rf = root / "reports" / "parity", root / "reports" / "ftp"
    parity.main(["--jsonl", str(jsonl), "--out", str(rp / "dev-zero-fix")])
    parity.main(["--jsonl", str(jsonl), "--out", str(rp / "dev-cluster"), "--cluster-report"])
    parity.main(["--jsonl", str(jsonl), "--out", str(rp / "dev-recint-diag"), "--recint-diag"])
    ftp.main(["evaluate", "--units", str(units), "--split", "dev", "--out", str(rf / "dev")])
    ftp.main(["calibrate", "--units", str(units), "--out", str(rf / "r1-intervals")])
    ftp.main(["baseline", "--units", str(units), "--split", "dev", "--out", str(rf / "dev-persistence")])
    return root


# 테스트마다 위 폴더를 복사해 쓴다(한 테스트가 바꾼 보고서가 다른 테스트에 남지 않게).
@pytest.fixture
def repo(committed_repo: Path, tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    shutil.copytree(committed_repo, root)
    return root


# 폴더 안 모든 파일의 {상대 경로: 바이트}. 실행 전후가 같으면 도구가 저장소에 아무것도 쓰거나 지우지 않은
# 것이다.
def _snapshot(root: Path) -> dict[str, bytes]:
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(root.rglob("*")) if p.is_file()}


# parity.main·ftp.main을 감싸 도구가 넘긴 --out 경로를 모은다(원래 함수는 그대로 부른다).
def _record_outputs(monkeypatch) -> list[Path]:
    outs: list[Path] = []
    for module in (parity, ftp):
        original = module.main

        def spy(argv, original=original):
            outs.append(Path(argv[argv.index("--out") + 1]))
            return original(argv)

        monkeypatch.setattr(module, "main", spy)
    return outs


COMMITTED_FILES = [
    f"reports/{d}/{n}{ext}"
    for d, n in [
        ("parity", "dev-zero-fix"),
        ("parity", "dev-cluster"),
        ("parity", "dev-recint-diag"),
        ("ftp", "dev"),
        ("ftp", "r1-intervals"),
        ("ftp", "dev-persistence"),
    ]
    for ext in (".json", ".md")
]


# 같은 입력이면 12개 파일이 모두 바이트까지 같아 0을 돌려주고, 다시 만든 파일은 임시 폴더에만 쓰였다가 끝나면
# 지워지며, 저장소 폴더(커밋된 보고서와 입력)는 한 바이트도 바뀌지 않아야 한다.
def test_check_passes_and_writes_only_to_a_temporary_folder(repo, tmp_path, monkeypatch, capsys):
    temp_base = tmp_path / "temp"
    temp_base.mkdir()
    monkeypatch.setattr(tempfile, "tempdir", str(temp_base))
    outs = _record_outputs(monkeypatch)
    before = _snapshot(repo)

    assert reproduce.main(["--root", str(repo), "--check"]) == 0

    assert _snapshot(repo) == before
    assert len(outs) == 6
    assert all(o.resolve().is_relative_to(temp_base.resolve()) for o in outs)
    assert not [p for p in temp_base.iterdir() if p.name.startswith(reproduce.TEMP_PREFIX)]
    out = capsys.readouterr().out
    for name in COMMITTED_FILES:
        assert f"same   {name}" in out
    assert "DIFF" not in out


# 커밋된 JSON의 값 하나를 바꾸면(단계마다 하나씩) 1을 돌려주고, 바뀐 값의 경로와 두 값을 출력해야 한다. 도구는
# 커밋된 파일을 고치지 않는다(바꾼 내용이 그대로 남는다). 다른 단계는 --stage로 돌리지 않는다.
@pytest.mark.parametrize(
    ("stage", "path", "keys", "where"),
    [
        ("ftp-dev", "reports/ftp/dev.json", ["methods", "R1", "mae", "point"], "methods.R1.mae.point"),
        ("parity-dev", "reports/parity/dev-zero-fix.json", ["rides"], "rides"),
    ],
)
def test_check_fails_with_the_changed_value(repo, capsys, stage, path, keys, where):
    committed = repo / path
    data = json.loads(committed.read_text(encoding="utf-8"))
    node = data
    for k in keys[:-1]:
        node = node[k]
    old = node[keys[-1]]
    node[keys[-1]] = old + 1
    committed.write_text(json.dumps(data, indent=1, ensure_ascii=False), encoding="utf-8", newline="\n")
    before = _snapshot(repo)

    assert reproduce.main(["--root", str(repo), "--stage", stage, "--check"]) == 1

    assert _snapshot(repo) == before
    out = capsys.readouterr().out
    assert f"DIFF   {path}: 1 value differs" in out
    assert f"{where}: committed {json.dumps(old + 1)}, reproduced {json.dumps(old)}" in out


# 값이 같아도 바이트가 다르면(여기서는 줄 끝을 CRLF로) 다른 파일로 센다. 정확 비교는 바이트 단위라는 약속이다.
# Markdown은 손으로 한 줄을 더하면 그 줄이 차이로 나와야 한다.
def test_formatting_and_markdown_differences_are_reported(repo, capsys):
    persistence = repo / "reports" / "ftp" / "dev-persistence.json"
    persistence.write_bytes(persistence.read_bytes().replace(b"\n", b"\r\n"))
    recint = repo / "reports" / "parity" / "dev-recint-diag.md"
    recint.write_text(recint.read_text(encoding="utf-8") + "edited by hand\n", encoding="utf-8", newline="\n")

    assert reproduce.main(["--root", str(repo), "--check"]) == 1

    out = capsys.readouterr().out
    assert "DIFF   reports/ftp/dev-persistence.json: same values, different bytes" in out
    assert "DIFF   reports/parity/dev-recint-diag.md" in out
    assert "-edited by hand" in out
    assert "same   reports/ftp/dev.json" in out
    assert "result: 2 of 12 files differ" in out


# 입력이 없으면 만드는 명령을 안내하고, 다른 단계는 그대로 확인한 뒤 2를 돌려준다(차이가 없을 때). 없는 입력을
# 새로 만들거나 저장소에 무엇을 쓰지 않는다.
def test_missing_input_prints_how_to_make_it(repo, capsys):
    (repo / "reports" / "parity" / "dev.jsonl").unlink()
    before = _snapshot(repo)

    assert reproduce.main(["--root", str(repo), "--check"]) == 2

    assert _snapshot(repo) == before
    out = capsys.readouterr().out
    assert "parity-dev: input reports/parity/dev.jsonl not found" in out
    assert "./gradlew -q parity" in out
    assert "--athletes ../manifests/parity_dev.txt --out ../reports/parity/dev.jsonl" in out
    assert "same   reports/ftp/dev.json" in out
    assert "dev-zero-fix.json" not in out


# --check 없이 --out-dir만 주면 비교 없이 그 폴더에 다시 만든 보고서를 남긴다(커밋된 것과 바이트가 같다). 출력
# 폴더가 reports/ 안이거나, --check도 --out-dir도 없으면 아무것도 하지 않고 옵션 오류(종료 코드 2)로 끝낸다.
def test_out_dir_keeps_the_files_and_reports_dir_is_refused(repo, tmp_path, capsys):
    keep = tmp_path / "keep"
    before = _snapshot(repo)

    assert reproduce.main(["--root", str(repo), "--stage", "parity-dev", "--out-dir", str(keep)]) == 0

    for name in ("dev-zero-fix", "dev-cluster", "dev-recint-diag"):
        for ext in (".json", ".md"):
            kept = keep / "parity-dev" / f"{name}{ext}"
            assert kept.read_bytes() == (repo / "reports" / "parity" / f"{name}{ext}").read_bytes()
    for argv in (
        ["--root", str(repo), "--check", "--out-dir", str(repo / "reports" / "parity")],
        ["--root", str(repo), "--check", "--out-dir", str(repo / "reports")],
        ["--root", str(repo)],
    ):
        with pytest.raises(SystemExit) as e:
            reproduce.main(argv)
        assert e.value.code == 2
    assert _snapshot(repo) == before
    assert "reports" in capsys.readouterr().err


# 단계는 개발 세트 두 개뿐이다: 봉인 세트(sealed)·봉인 test를 다시 돌리는 단계나 옵션이 없고, 입력과 커밋된
# 보고서 경로에도 sealed·test가 없다. 모르는 단계 이름은 옵션 오류다.
def test_only_development_set_stages_exist(repo):
    assert set(reproduce.STAGES) == {"parity-dev", "ftp-dev"}
    for stage in reproduce.STAGES.values():
        paths = [stage.input] + [r.committed for r in stage.reports]
        assert not [p for p in paths if "sealed" in p or "test" in Path(p).name]
        for r in stage.reports:
            if "--split" in r.args:
                assert r.args[r.args.index("--split") + 1] == "dev"
    for bad in ("ftp-test", "parity-sealed"):
        with pytest.raises(SystemExit) as e:
            reproduce.main(["--root", str(repo), "--stage", bad, "--check"])
        assert e.value.code == 2


# 값 비교 함수: 중첩 dict·list에서 다른 곳의 경로와 두 값을 내고, NaN끼리는 같다고 보며, 1과 1.0처럼 형이
# 다르면 다르게 본다(JSON 바이트가 달라지므로). 한쪽에만 있는 키와 길이가 다른 리스트도 차이로 낸다.
def test_json_differences():
    committed = {"a": 1, "b": {"c": [1.0, 2.0, 3.0], "d": float("nan")}, "e": "x", "f": True}
    same = json.loads(json.dumps(committed))
    assert reproduce.json_differences(committed, same) == []
    reproduced = {"a": 1.0, "b": {"c": [1.0, 2.5], "d": float("nan")}, "f": True, "g": None}
    assert reproduce.json_differences(committed, reproduced) == [
        ("a", 1, 1.0),
        ("b.c[1]", 2.0, 2.5),
        ("b.c (length)", 3, 2),
        ("e", "x", reproduce.MISSING),
        ("g", reproduce.MISSING, None),
    ]
    assert reproduce.json_differences([1, 2], [1, 3]) == [("[1]", 2, 3)]
    assert reproduce.json_differences(True, 1) == [("(whole file)", True, 1)]
