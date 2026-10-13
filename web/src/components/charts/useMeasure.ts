// [파일 역할] 차트 세 개(PowerCurveChart, TimeSeriesChart, TrainingLoadChart)가 공유하는 훅. 감싸는 요소의
// 실제 가로 폭을 재서 돌려주고, 요소 크기가 바뀌면 다시 잰다.
// [왜 필요한가] D3 스케일은 픽셀 범위(range)를 알아야 축 눈금·선 좌표를 계산할 수 있다. 차트는 SVG에 숫자
// 폭을 주고 그 폭으로 스케일을 만들므로, 폭을 state로 들고 있다가 바뀌면 다시 그려 반응형 차트를 만든다.

import { useEffect, useRef, useState } from "react";

// 반환값: [요소에 붙일 ref, 현재 폭(px)]. 첫 렌더에서는 아직 요소가 없어 폭이 0이므로, 차트 쪽은
// Math.max(width, 320)으로 최소 폭을 보장한다.
/** Width of a container element, updated on resize (for responsive SVG charts). */
export function useMeasure<T extends HTMLElement>(): [React.RefObject<T | null>, number] {
  const ref = useRef<T | null>(null);
  const [width, setWidth] = useState(0);
  // 마운트 후 한 번: 현재 폭을 바로 읽고, ResizeObserver로 요소 크기 변화를 구독한다. 창 resize 이벤트와 달리
  // 요소 자체의 크기 변화(레이아웃 변경 포함)를 잡는다. ResizeObserver가 없는 환경(테스트용 jsdom 등)에서는
  // 처음 폭만 쓴다. 언마운트 때 disconnect로 구독을 정리한다.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    setWidth(el.clientWidth);
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver((entries) => {
      for (const e of entries) setWidth(e.contentRect.width);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width];
}
