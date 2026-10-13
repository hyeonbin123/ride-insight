"""Decode sample FIT files with python-fitparse and write reference JSON for the Kotlin decoder tests.

Usage: ``python -m ride_insight_ml.fit_expected --fit <dir with .fit> --out <dir for .json>``
(the repository uses ``server/src/test/resources/fit`` and its ``expected/`` sub-folder).

For each ``*.fit``: ``file_id`` fields, ``sport``/``session`` summaries, every ``record`` message as
parallel arrays (timestamp as Unix seconds, power, heart_rate, cadence, distance m, altitude m,
speed m/s; ``null`` where the field is absent or invalid) and, for files fitparse rejects, the
exception type. Only fields the Kotlin decoder is expected to produce are written.
"""

# [파일 역할] 샘플 FIT 파일을 python-fitparse로 디코딩해 Kotlin FIT 디코더 테스트가 비교할 기대값 JSON을
# 만드는 일회성 도구다. 결과는 server/src/test/resources/fit/expected/*.json에 들어 있고, server의
# FitDecoderTest가 이 JSON을 읽어 레코드 수·각 필드 값을 1e-6 안에서 대조한다.
# [왜 필요한가] FIT 디코더는 Garmin FIT SDK의 라이선스 때문에 직접 구현했다(README, docs/plan.md). 직접 쓴
# 디코더가 맞는지 판단할 외부 기준이 필요해서, 널리 쓰이는 MIT 라이선스 구현(python-fitparse)의 결과를 정답
# 삼아 비교한다. docs/ai-verification.md에 따르면 이 대조 테스트가 threshold_power 필드 번호(112 → 45)와
# 개발자 필드 정의 크기(2 → 3바이트) 버그 두 개를 잡았다.
# [흐름] --fit 폴더의 *.fit을 하나씩 decode() → file_id·session·sport 요약과 record 시계열을 평행 배열로 정리
# → 파일마다 같은 이름의 .json으로 저장. fitparse가 거절하는 손상 파일은 예외 이름만 남긴다.

from __future__ import annotations

# 표준 라이브러리: 명령행 인자(argparse), JSON 저장(json), FIT의 datetime을 Unix 초로 바꾸기(datetime, UTC),
# 경로 처리(pathlib).
import argparse
import json
from datetime import UTC, datetime
from pathlib import Path

# fitparse: 기대값을 만드는 기준 구현. ml의 dev 의존성(pyproject.toml의 dependency-groups.dev)에만 들어 있어
# 이 도구를 돌릴 때만 필요하다.
import fitparse

# RECORD_FIELDS: record(초 단위 기록) 메시지에서 뽑을 필드. Kotlin 디코더의 FitRecordSample이 내놓는 값(power,
# heartRate, cadence, distance, speed, altitude)과 1:1로 맞춘 목록이다. 디코더가 만들지 않는 필드는 넣지 않아
# 비교 대상을 좁힌다.
# SESSION_FIELDS: 라이딩 요약(session 메시지) 필드. 기기가 계산한 NP·TSS·IF와 threshold_power(FTP)도 들어
# 있다. Kotlin 테스트는 세션 수와 이 필드들을 대조하며, threshold_power 필드 번호 오류(112 → 45)가 이 비교로
# 드러났다(docs/ai-verification.md).
RECORD_FIELDS = ["power", "heart_rate", "cadence", "distance", "speed", "altitude"]
SESSION_FIELDS = [
    "sport",
    "sub_sport",
    "start_time",
    "total_elapsed_time",
    "total_timer_time",
    "total_distance",
    "avg_power",
    "max_power",
    "normalized_power",
    "training_stress_score",
    "intensity_factor",
    "threshold_power",
    "total_ascent",
]
# FILE_ID_FIELDS: 파일 종류(activity 등)와 제조사·제품을 담은 file_id 메시지 필드. Kotlin 테스트가 디코더의
# 파일 종류·제조사 값과 대조한다.
FILE_ID_FIELDS = ["type", "manufacturer", "product", "serial_number", "time_created"]


# fitparse 값을 JSON에 쓸 수 있는 단순한 값으로 바꾼다.
# - datetime → Unix 초(int). tzinfo가 없는 값은 UTC로 간주한다(FIT 타임스탬프는 UTC 기준이다). Kotlin 쪽
#   timestampUnix와 정수로 바로 비교하려는 것이다.
# - float → 소수 6자리 반올림. Kotlin 테스트의 허용 오차(1e-6)에 맞춰 파일 크기를 줄이면서 비교에는 영향이
#   없게 한다.
# - 그 밖의 값(int, str, None)은 그대로 둔다.
def _plain(v):
    if isinstance(v, datetime):
        return int(v.replace(tzinfo=UTC).timestamp()) if v.tzinfo is None else int(v.timestamp())
    if isinstance(v, float):
        return round(v, 6)
    return v


# 메시지에서 필드 하나를 읽어 _plain으로 바꾼다. prefer_enhanced=True이면 enhanced_<이름>
# 필드(speed·altitude의 넓은 범위 판)를 먼저 보고, 없을 때만 기본 필드를 쓴다. 기기에 따라 둘 중 하나만
# 기록하기 때문이다.
def _get(msg, name, prefer_enhanced=False):
    if prefer_enhanced:
        enhanced = msg.get_value(f"enhanced_{name}")
        if enhanced is not None:
            return _plain(enhanced)
    return _plain(msg.get_value(name))


# FIT 파일 하나를 디코딩해 기대값 dict를 돌려준다. 호출 위치: 아래 main().
# 출력 구조: file, file_id, sessions(목록), sports(목록), records(필드별 평행 배열), record_count. 디코딩에
# 실패하면 file, error(예외 클래스 이름), error_message(앞 200자)만 담는다.
# 평행 배열(필드마다 리스트 하나)로 두는 이유: 레코드가 수천~수만 개라 레코드마다 객체를 만드는 것보다 JSON이
# 작고, Kotlin 테스트가 인덱스 i로 필드별 값을 바로 꺼낼 수 있다.
def decode(path: Path) -> dict:
    out: dict = {"file": path.name}
    # check_crc=False: CRC 판정은 Kotlin 디코더가 따로 하고(crcValid) 여기서는 내용만 기대값으로 뽑으려고
    # fitparse의 CRC 검사를 끈다. parse()로 파일 전체를 한 번에 읽어 손상 파일이면 여기서 예외가 난다.
    try:
        fit = fitparse.FitFile(str(path), check_crc=False)
        fit.parse()
    except Exception as e:  # noqa: BLE001 - we record the failure kind
        # 손상 파일(잘린 파일, 헤더 손상)은 '이 파일은 거절돼야 한다'는 기대값 자체가 의미가 있으므로 예외
        # 종류와 메시지 일부만 남기고 끝낸다.
        out["error"] = type(e).__name__
        out["error_message"] = str(e)[:200]
        return out
    # file_id는 보통 파일 맨 앞에 하나 있으므로 첫 메시지만 쓴다.
    file_ids = list(fit.get_messages("file_id"))
    if file_ids:
        out["file_id"] = {k: _plain(file_ids[0].get_value(k)) for k in FILE_ID_FIELDS}
    sessions = []
    for s in fit.get_messages("session"):
        sessions.append({k: _plain(s.get_value(k)) for k in SESSION_FIELDS})
    out["sessions"] = sessions
    out["sports"] = [{"sport": _plain(m.get_value("sport"))} for m in fit.get_messages("sport")]
    # record 메시지를 시간 순서대로 돌며 필드별 배열에 값을 붙인다. 값이 없거나 무효(fitparse가 None을
    # 돌려줌)이면 null이 들어가 Kotlin 쪽에서 '이 위치는 값이 없어야 한다'로 검사된다.
    records = {k: [] for k in ["timestamp", *RECORD_FIELDS]}
    for r in fit.get_messages("record"):
        records["timestamp"].append(_get(r, "timestamp"))
        for k in RECORD_FIELDS:
            records[k].append(_get(r, k, prefer_enhanced=k in ("speed", "altitude")))
    out["records"] = records
    out["record_count"] = len(records["timestamp"])
    return out


# 명령행 진입점. --fit 폴더의 *.fit을 이름순으로 디코딩해 --out 폴더에 <파일 이름>.json으로 쓴다. 이름순으로
# 처리해 출력 순서가 실행마다 같다. argv를 받게 해 둔 것은 다른 코드에서 인자 목록으로 직접 부를 수 있게
# 하려는 것이다.
def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--fit", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args(argv)
    a.out.mkdir(parents=True, exist_ok=True)
    for path in sorted(a.fit.glob("*.fit")):
        result = decode(path)
        # separators=(",", ":")로 공백 없는 압축 JSON을 쓴다. 저장소에 커밋되는 테스트 리소스라 크기를 줄인다.
        (a.out / f"{path.stem}.json").write_text(json.dumps(result, separators=(",", ":")), encoding="utf-8")
        print(
            path.name,
            result.get("error") or f"records={result['record_count']} sessions={len(result['sessions'])}",
        )


if __name__ == "__main__":
    main()
