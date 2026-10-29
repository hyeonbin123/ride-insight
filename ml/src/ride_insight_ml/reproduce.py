"""한 명령 재현 확인: 개발 세트 보고서를 다시 만들어 커밋된 보고서와 바이트 단위로 비교한다.

사용법(ml 폴더에서)::

    uv run python -m ride_insight_ml.reproduce --check                     # 두 단계 모두
    uv run python -m ride_insight_ml.reproduce --stage parity-dev --check  # 1단계 파리티만
    uv run python -m ride_insight_ml.reproduce --stage ftp-dev --check     # 3단계 FTP만

단계와 다시 만드는 보고서(입력은 커밋하지 않는 로컬 중간 파일이다):

* ``parity-dev``  입력 reports/parity/dev.jsonl(Kotlin parity 태스크가 쓴 개발 세트 라이딩별 JSONL).
                  parity.py의 등록된 분석(reports/parity/dev-zero-fix), 선수 군집 사후
                  보고(dev-cluster), GoldenCheetah 암묵 기록 간격 진단(dev-recint-diag)
* ``ftp-dev``     입력 data/features/units.parquet(ftp build가 만든 예측 단위). ftp.py evaluate --split
                  dev(reports/ftp/dev), calibrate(r1-intervals), baseline --split dev(dev-persistence)

보고서마다 docs/experiments.md에 적힌 기록 명령을 그대로 부르되 출력만 임시 폴더(또는 --out-dir)로 돌리고,
.json과 .md를 커밋된 파일과 바이트 단위로 비교한다. 커밋된 보고서와 입력은 읽기만 한다. 봉인 세트(sealed)와
봉인 test는 다시 돌리지 않는다(그런 단계가 없다). CPU 1~2분.

종료 코드: 0 = 비교한 파일이 모두 바이트까지 같다, 1 = 다른 파일이 있다(JSON은 다른 값의 경로와 두 값,
Markdown은 다른 줄을 출력), 2 = 입력 파일이 없다(만드는 명령을 출력) 또는 옵션 오류.
"""

# [파일 역할] 커밋된 측정 기록(reports/의 개발 세트 보고서)이 같은 입력과 지금 코드로 다시 나오는지를 한
# 명령으로 확인하는 도구. '규칙을 먼저 커밋하고 한 번만 잰다'는 방법론에서, 커밋된 수치가 손으로 고친 값이
# 아니라 코드가 낸 값이라는 것을 저장소를 받은 사람이 직접 확인할 수 있게 한다.
# [왜 이렇게 했나]
# - 보고서를 만드는 코드를 새로 쓰지 않고 각 모듈의 명령행 진입점(parity.main, ftp.main)을 기록 명령과 같은
#   인자로 부른다. 직렬화(json.dumps의 indent·ensure_ascii, 줄 끝 LF)와 Markdown 제목까지 기록 때와 같은
#   경로를 타야 바이트 비교가 의미가 있다. 하위 프로세스 대신 같은 프로세스에서 부르는 것은 테스트에서 출력
#   경로를 확인하고 실행 시간을 줄이려는 것이다.
# - 다시 만든 파일은 임시 폴더에만 쓴다. reports/에 쓰면 확인하려던 기록을 덮어쓰게 되므로 --out-dir도
#   reports/ 안은 거부한다.
# - 비교는 바이트 단위다. 2026-10-30에 잠금 환경(ml/uv.lock, Python 3.11)에서 이 도구가 다루는 12개 파일이
#   모두 바이트까지 같게 다시 나오는 것을 확인했다. 다른 경우의 원인을 보이도록 JSON은 값 단위 차이(경로와 두
#   값), Markdown은 다른 줄을 함께 출력한다.
# - 입력(dev.jsonl, units.parquet)은 OpenData zip(약 12 GB)에서 Kotlin 도구로 만드는 큰 로컬 파일이라 이
#   도구가 다시 만들지 않는다. 없으면 만드는 명령을 안내하고 종료 코드 2로 끝낸다.
# [주의]
# - 실행 메시지는 저장소의 다른 도구처럼 영어로 쓴다. 차이 목록에는 보고서의 한국어와 기호(−, ≤ 등)가 섞여,
#   Windows에서 출력을 파이프로 넘기면 stdout이 cp949 같은 코드 페이지라 인코딩 오류로 멈출 수 있다. 그래서
#   main에서 stdout을 errors="replace"로 바꾼다.
# - 입력을 지금 코드로 다시 만들면(예: Kotlin 구현이 바뀐 뒤 dev.jsonl을 새로 만들면) 커밋된 보고서와 다를 수
#   있다. 그 차이는 이 도구가 아니라 입력을 만든 코드의 변화다.

from __future__ import annotations

import argparse
import contextlib
import difflib
import io
import json
import math
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

from ride_insight_ml import ftp, parity

# 저장소 루트(ml/src/ride_insight_ml/reproduce.py의 세 단계 위). uv가 이 프로젝트를 편집 가능(editable) 모드로
# 설치하므로 __file__은 저장소 안의 소스 파일을 가리킨다. 다른 위치에서 돌릴 때는 --root로 준다.
REPO_ROOT = Path(__file__).resolve().parents[3]
# 임시 폴더 이름의 앞부분(테스트가 실행 뒤 남은 폴더가 없는지 찾는 데도 쓴다).
TEMP_PREFIX = "ride-insight-reproduce-"
# 파일 하나에 출력할 차이 줄 수의 상한. 넘으면 남은 개수만 적는다.
MAX_LISTED = 20
# 보고서 하나가 쓰는 파일 확장자. 모든 기록 명령이 같은 이름으로 .json과 .md를 쓴다.
EXTENSIONS = (".json", ".md")
# 명령행 진입점을 찾을 모듈. 함수가 아니라 모듈을 두고 부를 때마다 .main을 찾는다(테스트가 감싼 함수도
# 쓰이게).
MODULES = {"parity": parity, "ftp": ftp}


class _Missing:
    """한쪽 JSON에만 있는 키를 나타내는 값(None은 JSON null과 겹쳐서 따로 둔다)."""

    def __repr__(self) -> str:
        return "(missing)"


MISSING = _Missing()


@dataclass(frozen=True)
class Report:
    """다시 만들 보고서 하나.

    committed: 커밋된 보고서의 저장소 상대 경로(확장자 없이, 예: reports/ftp/dev). 끝 이름이 출력 이름이 된다.
    module: 부를 모듈 이름(MODULES의 키). args: 그 모듈 main에 넘길 인자. {input}은 단계의 입력 파일, {out}은
    출력 경로(확장자 없이)로 바뀐다. 인자는 docs/experiments.md에 적힌 기록 명령과 같다.
    """

    committed: str
    module: str
    args: tuple[str, ...]

    @property
    def name(self) -> str:
        return self.committed.rsplit("/", 1)[-1]

    def argv(self, input_path: Path, out: Path) -> list[str]:
        return [str(input_path) if a == "{input}" else str(out) if a == "{out}" else a for a in self.args]

    def command(self, input_rel: str) -> str:
        """출력에 보여 줄 명령(입력은 저장소 상대 경로, 출력 폴더는 <out>으로 줄여 쓴다)."""
        args = [
            input_rel if a == "{input}" else f"<out>/{self.name}" if a == "{out}" else a for a in self.args
        ]
        return f"python -m ride_insight_ml.{self.module} " + " ".join(args)


@dataclass(frozen=True)
class Stage:
    """같은 입력 파일에서 다시 만드는 보고서 묶음.

    input은 저장소 상대 경로, how_to_make는 입력이 없을 때 출력할 안내 줄들(입력을 만드는 명령)이다.
    """

    name: str
    input: str
    how_to_make: tuple[str, ...]
    reports: tuple[Report, ...]


# 단계 표. 개발 세트만 있다: 봉인 세트(reports/parity/sealed*)와 봉인 test(reports/ftp/test*)는 단계가 끝날 때
# 한 번만 재는 기록이라 이 도구로 다시 돌리지 않는다(README '측정 방식'). parity의 등록된 분석은 지금 비교
# 함수로 낸 dev-zero-fix와 비교한다(dev.json은 규칙 변경 9 전의 비교 함수로 낸 기록이라 지금 코드로는 다시
# 나오지 않는다).
STAGES: dict[str, Stage] = {
    s.name: s
    for s in (
        Stage(
            name="parity-dev",
            input="reports/parity/dev.jsonl",
            how_to_make=(
                "It is not committed: the Kotlin parity task writes it from the OpenData zips",
                "of the 100 development athletes. Download the sample and build the ride index",
                "first (README, 'data'), then:",
                '  cd server && ./gradlew -q parity --args="--zips ../data/opendata'
                " --index ../data/opendata_index --athletes ../manifests/parity_dev.txt"
                ' --out ../reports/parity/dev.jsonl"',
                "A JSONL made now comes from the current Kotlin code; the committed reports were",
                "made from the JSONL of 2026-10-10 (docs/experiments.md, stage 1), so a",
                "difference may come from code changes.",
            ),
            reports=(
                Report("reports/parity/dev-zero-fix", "parity", ("--jsonl", "{input}", "--out", "{out}")),
                Report(
                    "reports/parity/dev-cluster",
                    "parity",
                    ("--jsonl", "{input}", "--out", "{out}", "--cluster-report"),
                ),
                Report(
                    "reports/parity/dev-recint-diag",
                    "parity",
                    ("--jsonl", "{input}", "--out", "{out}", "--recint-diag"),
                ),
            ),
        ),
        Stage(
            name="ftp-dev",
            input="data/features/units.parquet",
            how_to_make=(
                "It is not committed: ftp build makes it from the ride features of the sample",
                "athletes. Download the sample and build the ride index first (README, 'data'),",
                "then export the features with the Kotlin parity task (--athletes: a file with",
                "the ids in manifests/opendata_sample.json, one per line) and build the units:",
                '  cd server && ./gradlew -q parity --args="--zips ../data/opendata'
                ' --index ../data/opendata_index --athletes <ids file> --out ../data/features/all.jsonl"',
                "  cd ml && uv run python -m ride_insight_ml.ftp build --features ../data/features/all.jsonl"
                " --index ../data/opendata_index --sample ../manifests/opendata_sample.json"
                " --out ../data/features/units.parquet",
            ),
            reports=(
                Report(
                    "reports/ftp/dev",
                    "ftp",
                    ("evaluate", "--units", "{input}", "--split", "dev", "--out", "{out}"),
                ),
                Report(
                    "reports/ftp/r1-intervals", "ftp", ("calibrate", "--units", "{input}", "--out", "{out}")
                ),
                Report(
                    "reports/ftp/dev-persistence",
                    "ftp",
                    ("baseline", "--units", "{input}", "--split", "dev", "--out", "{out}"),
                ),
            ),
        ),
    )
}


# 두 스칼라가 JSON에서 같은 값인가. 형이 다르면 다르다고 본다: 1과 1.0, True와 1은 파이썬에서 ==지만 JSON
# 바이트가 다르다. NaN끼리는 같다고 본다(json이 NaN을 쓰고 읽을 수 있고, NaN != NaN이라 그대로 비교하면 늘
# 다르게 나온다).
def _same_scalar(a, b) -> bool:
    if type(a) is not type(b):
        return False
    if isinstance(a, float) and math.isnan(a) and math.isnan(b):
        return True
    return a == b


# 두 JSON 값의 차이를 (경로, 커밋된 값, 다시 만든 값) 목록으로 돌려준다. 같으면 빈 목록.
# 경로는 키를 점으로, 리스트 위치를 [i]로 잇는다(예: methods.R1.mae.point, worst_np_1s[3].athlete). 한쪽에만
# 있는 키는 MISSING으로, 길이가 다른 리스트는 겹치는 앞부분을 비교한 뒤 '<경로> (length)'로 두 길이를 낸다. 맨
# 위 값 자체가 다르면 경로는 '(whole file)'이다. dict 키 순서는 보지 않는다(키 순서만 바뀌면 바이트 비교에서
# 따로 잡힌다).
def json_differences(committed, reproduced, path: str = "") -> list[tuple[str, object, object]]:
    if isinstance(committed, dict) and isinstance(reproduced, dict):
        found: list[tuple[str, object, object]] = []
        for k, v in committed.items():
            p = f"{path}.{k}" if path else str(k)
            found += json_differences(v, reproduced[k], p) if k in reproduced else [(p, v, MISSING)]
        for k, v in reproduced.items():
            if k not in committed:
                found.append((f"{path}.{k}" if path else str(k), MISSING, v))
        return found
    if isinstance(committed, list) and isinstance(reproduced, list):
        found = []
        for i, (a, b) in enumerate(zip(committed, reproduced, strict=False)):
            found += json_differences(a, b, f"{path}[{i}]")
        if len(committed) != len(reproduced):
            found.append((f"{path} (length)", len(committed), len(reproduced)))
        return found
    return [] if _same_scalar(committed, reproduced) else [(path or "(whole file)", committed, reproduced)]


# 차이 한 줄의 값을 보여 줄 문자열. JSON 표기 그대로(문자열은 따옴표 포함) 쓰고 너무 길면 자른다.
def _show(v) -> str:
    if v is MISSING:
        return repr(v)
    s = json.dumps(v, ensure_ascii=False)
    return s if len(s) <= 80 else s[:77] + "..."


# JSON 차이 한 줄. 둘 다 숫자(bool 제외)면 다시 만든 값 − 커밋된 값을 덧붙인다.
def _describe(path: str, a, b) -> str:
    line = f"{path}: committed {_show(a)}, reproduced {_show(b)}"
    numbers = all(isinstance(x, int | float) and not isinstance(x, bool) for x in (a, b))
    return f"{line} (difference {b - a:+.6g})" if numbers else line


# 커밋된 파일과 다시 만든 파일을 비교한다. 바이트가 같으면 빈 목록, 다르면 첫 줄이 요약이고 나머지가 자세한
# 차이인 줄 목록을 돌려준다(MAX_LISTED 줄까지). JSON은 값 단위 차이를, 값이 같으면 '서식만 다름'을, Markdown은
# 통합 diff(커밋된 쪽이 -, 다시 만든 쪽이 +)를 낸다.
def compare_file(committed: Path, reproduced: Path) -> list[str]:
    if not committed.is_file():
        return ["committed file not found"]
    if not reproduced.is_file():
        return ["the command did not write this file"]
    old, new = committed.read_bytes(), reproduced.read_bytes()
    if old == new:
        return []
    old_text, new_text = old.decode("utf-8", errors="replace"), new.decode("utf-8", errors="replace")
    if committed.suffix == ".json":
        try:
            diffs = json_differences(json.loads(old_text), json.loads(new_text))
        except json.JSONDecodeError as e:
            return [f"not valid JSON ({e})"]
        if not diffs:
            return ["same values, different bytes (formatting, key order or line endings)"]
        head = f"{len(diffs)} value{'s' if len(diffs) > 1 else ''} differ{'' if len(diffs) > 1 else 's'}"
        lines = [_describe(*d) for d in diffs[:MAX_LISTED]]
        more = [f"... and {len(diffs) - MAX_LISTED} more"] if len(diffs) > MAX_LISTED else []
        return [head, *lines, *more]
    if old_text.splitlines() == new_text.splitlines():
        return ["same lines, different line endings"]
    diff = list(
        difflib.unified_diff(
            old_text.splitlines(), new_text.splitlines(), "committed", "reproduced", lineterm="", n=0
        )
    )
    more = [f"... and {len(diff) - MAX_LISTED} more diff lines"] if len(diff) > MAX_LISTED else []
    return ["text differs", *diff[:MAX_LISTED], *more]


# 보고서 하나를 기록 명령으로 다시 만든다(out_dir/<이름>.json·.md). 각 도구가 출력하는 'wrote ...' 줄은 임시
# 경로라 이 도구의 출력에 섞지 않는다. 도구 안의 예외는 그대로 올라간다(입력이 깨졌으면 그 오류가 원인을 보여
# 준다).
def regenerate(report: Report, input_path: Path, out_dir: Path) -> None:
    with contextlib.redirect_stdout(io.StringIO()):
        MODULES[report.module].main(report.argv(input_path, out_dir / report.name))


# 명령행 진입점. 종료 코드를 돌려준다(아래 if __name__ 블록이 SystemExit로 넘긴다).
def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="python -m ride_insight_ml.reproduce",
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument(
        "--stage",
        choices=[*STAGES, "all"],
        default="all",
        help=(
            "다시 만들 단계. all(기본)은 parity-dev와 ftp-dev를 차례로 돈다. 봉인 세트·봉인 test 단계는 없다"
        ),
    )
    p.add_argument(
        "--check",
        action="store_true",
        help=(
            "다시 만든 .json·.md를 커밋된 보고서와 바이트 단위로 비교하고 "
            "결과를 종료 코드(0 같음, 1 다름)로 낸다"
        ),
    )
    p.add_argument(
        "--out-dir",
        type=Path,
        default=None,
        help=(
            "다시 만든 보고서를 남길 폴더(<폴더>/<단계>/<이름>.json·.md). 주지 않으면 임시 폴더에 "
            "쓰고 끝나면 지운다. 커밋된 보고서를 덮어쓰지 않도록 저장소의 reports/ 안은 거부한다. "
            "--check 없이 주면 비교 없이 다시 만들기만 한다"
        ),
    )
    p.add_argument(
        "--root",
        type=Path,
        default=REPO_ROOT,
        help="저장소 루트(reports/와 data/가 있는 폴더). 기본은 이 파일 위치로 찾은 저장소",
    )
    a = p.parse_args(argv)
    if not a.check and a.out_dir is None:
        p.error("nothing to do: give --check (compare with the committed reports), --out-dir DIR, or both")
    root = a.root.resolve()
    if not (root / "reports").is_dir():
        p.error(f"{root} does not look like the repository root (no reports/ folder); give --root")
    # p.error는 사용법과 오류를 stderr에 쓰고 종료 코드 2로 끝낸다. 출력 폴더 검사는 무엇이든 쓰기
    # 전에 한다.
    if a.out_dir is not None and a.out_dir.resolve().is_relative_to(root / "reports"):
        p.error(
            f"--out-dir {a.out_dir} is inside {root / 'reports'}; "
            "the committed reports must not be overwritten"
        )
    # 파일 머리 주석의 [주의] 참고: 파이프로 넘긴 Windows 출력에서 코드 페이지에 없는 글자는 ?로 쓴다.
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        reconfigure(errors="replace")

    stages = list(STAGES.values()) if a.stage == "all" else [STAGES[a.stage]]
    compared = differing = missing = 0
    with contextlib.ExitStack() as stack:
        if a.out_dir is None:
            base = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix=TEMP_PREFIX)))
        else:
            base = a.out_dir.resolve()
        for stage in stages:
            input_path = root / stage.input
            if not input_path.is_file():
                missing += 1
                print(f"{stage.name}: input {stage.input} not found.")
                for line in stage.how_to_make:
                    print(f"  {line}")
                continue
            out_dir = base / stage.name
            out_dir.mkdir(parents=True, exist_ok=True)
            print(f"{stage.name}: input {stage.input}", flush=True)
            for report in stage.reports:
                print(f"  regenerating: {report.command(stage.input)}", flush=True)
                regenerate(report, input_path, out_dir)
                if not a.check:
                    continue
                for ext in EXTENSIONS:
                    compared += 1
                    rel = f"{report.committed}{ext}"
                    problems = compare_file(root / rel, out_dir / f"{report.name}{ext}")
                    if not problems:
                        print(f"  same   {rel}", flush=True)
                        continue
                    differing += 1
                    print(f"  DIFF   {rel}: {problems[0]}")
                    for line in problems[1:]:
                        print(f"           {line}")
        if a.out_dir is not None:
            print(f"regenerated reports are in {base}")
    if a.check and compared:
        if differing:
            print(f"result: {differing} of {compared} files differ from the committed reports")
        else:
            print(f"result: all {compared} files are byte-identical to the committed reports")
    if missing:
        print(f"{missing} stage{'s' if missing > 1 else ''} skipped because the input is missing")
    if differing:
        return 1
    return 2 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
