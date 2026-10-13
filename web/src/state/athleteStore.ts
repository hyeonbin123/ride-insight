// [파일 역할] 화면 전체가 공유하는 유일한 클라이언트 상태: 지금 작업 중인 선수 id. 메뉴바, 대시보드, 라이딩
// 목록, 선수 페이지가 이 값을 읽고, 선수 페이지가 바꾼다.
// [구조] 값 하나를 여러 컴포넌트가 구독하는 데 Provider 없이 훅 하나로 충분하고, 선택자(s => s.athleteId)로
// 필요한 값이 바뀔 때만 다시 그린다. 선수 정보 자체는 서버 데이터라 React Query가 맡고 여기에는 id만
// 둔다(인증이 없는 로컬 데모라 id가 곧 선수 선택이다).

import { create } from "zustand";

/**
 * The athlete the page is working with. The id lives in localStorage so a reload keeps the
 * selection; everything else comes from the API.
 */
interface AthleteState {
  athleteId: string | null;
  setAthleteId: (id: string | null) => void;
}

// localStorage 키. 앱 이름을 앞에 붙여 같은 주소(localhost)의 다른 앱 값과 겹치지 않게 한다.
const KEY = "ride-insight.athleteId";

// 저장된 선수 id를 읽는다. 사생활 보호 모드나 저장소가 막힌 환경에서는 localStorage 접근이 예외를 낼 수 있어
// null로 처리한다.
function readStored(): string | null {
  try {
    return window.localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

// 저장소 훅. 처음 값은 localStorage에서 읽고, setAthleteId는 localStorage에 쓰거나(null이면 지우고) 상태를
// 바꾼다. 저장이 실패해도 메모리 상태는 바뀌어 지금 세션에서는 동작한다.
export const useAthleteStore = create<AthleteState>((set) => ({
  athleteId: readStored(),
  setAthleteId: (id) => {
    try {
      if (id) window.localStorage.setItem(KEY, id);
      else window.localStorage.removeItem(KEY);
    } catch {
      // storage unavailable: keep the selection in memory only
    }
    set({ athleteId: id });
  },
}));
