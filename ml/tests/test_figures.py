"""README 그림(figures.py)이 커밋된 보고서의 수치를 그대로 그리는지 확인한다."""

# [테스트 내용] reports/ftp/test.json을 읽어 SVG를 만들고, 방법 7개의 이름과 보고서의 MAE·편향 값(소수
# 첫째 자리)이 글자로 들어 있는지, H5 판정 문구가 보고서와 같은지 본다. 그림이 보고서와 다른 숫자를 보여
# 주는 실수를 막는다. XML 파싱은 우리가 만든 문자열만 대상으로 한다(외부 입력 아님).

import json
import xml.etree.ElementTree as ET
from pathlib import Path

from ride_insight_ml.figures import LABELS, render

REPORT = Path(__file__).resolve().parents[2] / "reports" / "ftp" / "test.json"


def test_svg_shows_the_report_numbers():
    report = json.loads(REPORT.read_text(encoding="utf-8"))
    svg = render(report)
    ET.fromstring(svg)  # 올바른 XML인지(직접 만든 문자열)
    for name, label in LABELS.items():
        m = report["methods"][name]
        assert label in svg
        assert f"{m['mae']['point']:.1f} W · 편향 {m['bias']['point']:+.1f}" in svg
    assert report["H5"]["verdict"] in svg
    assert f"{report['n_units']:,}" in svg
