"""README 그림: 3단계 FTP 추정의 봉인 test 결과(방법별 MAE와 95% 구간, 편향)를 SVG로 그린다.

사용법::

    python -m ride_insight_ml.figures --report ../reports/ftp/test.json \\
        --out ../docs/figures/ftp-test-mae.svg
"""

# [파일 역할] 커밋된 측정 보고서(reports/ftp/test.json)의 수치만 읽어 그림 한 장을 만든다. 새로 재거나
# 계산하는 값은 없고(점 = 보고서의 MAE, 막대 = 보고서의 선수 단위 부트스트랩 95% 구간, 오른쪽 글자 =
# 보고서의 편향), 그림의 수치가 보고서와 같은지는 tests/test_figures.py가 확인한다.
# [왜 직접 SVG인가] 그림이 한 장이고 모양이 단순해(점·가로 막대·글자) 그림 라이브러리(matplotlib 등)를
# 의존성에 더하지 않았다. SVG는 GitHub README에서 그대로 보이고 글자가 선명하다. 글꼴은 지정하지 않아 보는
# 쪽의 기본 산세리프 글꼴로 그린다.
# [입력·출력] 입력은 ftp.py evaluate가 쓴 보고서 JSON(methods → 방법 이름 → mae.point/ci, bias.point,
# H5), 출력은 SVG 파일 하나.

from __future__ import annotations

import argparse
import json
from pathlib import Path

# 방법 이름 → 그림에 쓰는 설명(docs/experiments.md 3단계 후보 표와 같은 뜻).
LABELS = {
    "R1": "R1 20분×0.95 (서비스)",
    "R2": "R2 60분 최대",
    "R3": "R3 CP 모델",
    "R5": "R5 R1 선형 보정",
    "M1": "M1 LightGBM",
    "M1b": "M1b + W/kg",
    "M2": "M2 Ridge (채택 후보)",
}


# SVG 요소 하나를 문자열로. attrs의 밑줄은 하이픈으로 바꾼다(text_anchor → text-anchor).
def _el(tag: str, text: str | None = None, **attrs: object) -> str:
    a = " ".join(f'{k.replace("_", "-")}="{v}"' for k, v in attrs.items())
    return f"<{tag} {a}/>" if text is None else f"<{tag} {a}>{text}</{tag}>"


def render(report: dict) -> str:
    """보고서 dict → SVG 문자열. 방법은 R1, R2, R3, R5, M1, M1b, M2 순서로 위에서 아래로 그린다."""
    methods = report["methods"]
    names = [n for n in LABELS if n in methods]
    width, left, right, top, row = 840, 190, 160, 56, 34
    bottom = top + row * len(names)
    height = bottom + 56
    plot_w = width - left - right
    # x축: 0 W부터 가장 큰 구간 상한을 10 W 단위로 올린 값까지.
    xmax = (int(max(methods[n]["mae"]["ci"][1] for n in names) // 10) + 1) * 10

    def x(v: float) -> str:
        return f"{left + plot_w * v / xmax:.1f}"

    h5 = report.get("H5", {})
    title = (
        f"봉인 test(선수 {report['n_athletes']}명, 예측 단위 {report['n_units']:,}개): "
        "선수가 설정한 FTP와의 평균 절대 오차"
    )
    legend = "점: MAE, 막대: 선수 단위 부트스트랩 95% 구간, 오른쪽: 편향(예측 − 설정값)"
    head = f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}"'
    out = [
        f'{head} viewBox="0 0 {width} {height}" font-family="sans-serif" font-size="13">',
        _el("rect", width=width, height=height, fill="#ffffff"),
        _el("text", title, x=16, y=24, font_size=15, font_weight="bold"),
        _el("text", legend, x=16, y=44, fill="#555"),
    ]
    # 세로 격자와 x축 눈금(10 W마다).
    for v in range(0, xmax + 1, 10):
        out.append(_el("line", x1=x(v), y1=top - 8, x2=x(v), y2=bottom, stroke="#e0e0e0"))
        out.append(_el("text", str(v), x=x(v), y=bottom + 16, text_anchor="middle", fill="#666"))
    out.append(_el("text", "MAE (W)", x=x(xmax / 2), y=bottom + 34, text_anchor="middle", fill="#666"))
    for i, n in enumerate(names):
        m = methods[n]
        y = f"{top + row * i + row / 2:.1f}"
        ty = f"{top + row * i + row / 2 + 4:.1f}"
        lo, hi = m["mae"]["ci"]
        point = m["mae"]["point"]
        # 서비스가 쓰는 R1은 주황, 채택 후보는 파랑, 나머지는 회색.
        color = "#ef6c00" if n == "R1" else "#1565c0" if n == h5.get("adopted") else "#757575"
        out.append(_el("text", LABELS[n], x=left - 10, y=ty, text_anchor="end"))
        out.append(_el("line", x1=x(lo), y1=y, x2=x(hi), y2=y, stroke=color, stroke_width=3))
        out.append(_el("circle", cx=x(point), cy=y, r=5, fill=color))
        value = f"{point:.1f} W · 편향 {m['bias']['point']:+.1f}"
        out.append(_el("text", value, x=width - right + 10, y=ty, fill="#333"))
    if h5:
        d = h5["diff"]
        verdict = (
            f"H5 ({h5['adopted']} − R1): {d['point']:+.1f} W [{d['ci'][0]:+.1f}, {d['ci'][1]:+.1f}] "
            f"→ {h5['verdict']} (기준: 5 W 이상 낮음)"
        )
        out.append(_el("text", verdict, x=16, y=height - 8, fill="#555"))
    out.append("</svg>")
    return "\n".join(out) + "\n"


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--report", type=Path, required=True, help="ftp.py evaluate가 쓴 test 보고서 JSON")
    p.add_argument("--out", type=Path, required=True, help="쓸 SVG 경로(예: ../docs/figures/x.svg)")
    a = p.parse_args(argv)
    report = json.loads(a.report.read_text(encoding="utf-8"))
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(render(report), encoding="utf-8", newline="\n")
    print(a.out)


if __name__ == "__main__":
    main()
