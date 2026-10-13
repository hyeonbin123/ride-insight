// [파일 역할] 앱의 최상위 컴포넌트. 서버 데이터 캐시(React Query), MUI 테마, 라우터를 한곳에서 감싸고 화면
// 4개(대시보드, 라이딩 목록, 라이딩 상세, 선수·FTP)를 주소에 연결한다.
// [구성] 서버 상태(선수, 활동, 지표)는 React Query가 캐시하고, 화면 사이에 공유하는 클라이언트 상태(지금 고른
// 선수 id)는 Zustand(state/athleteStore.ts)가 맡는다. 두 종류의 상태를 나눠 서버 데이터를 전역 저장소에
// 복사해 두지 않는다.

// React Query: 서버 응답 캐시·로딩/오류 상태·무효화. MUI: 컴포넌트와 테마. react-router: 주소 ↔ 화면 연결.
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { CssBaseline, ThemeProvider, createTheme } from "@mui/material";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
// 공통 틀(상단 메뉴바)과 각 화면 컴포넌트.
import { Layout } from "./components/Layout";
import { ActivitiesPage } from "./pages/ActivitiesPage";
import { ActivityPage } from "./pages/ActivityPage";
import { AthletePage } from "./pages/AthletePage";
import { DashboardPage } from "./pages/DashboardPage";

// 앱 전체가 공유하는 쿼리 캐시. 기본 옵션:
// - retry: 1: 실패한 요청을 한 번만 다시 시도한다(React Query 기본은 3회). 실패를 빨리 화면에 보여 준다.
// - staleTime: 30초: 그 안에는 같은 데이터를 다시 받지 않고 캐시를 쓴다. 대신 업로드·FTP 변경·삭제 뒤에는 각
//   화면이 invalidateQueries로 직접 무효화한다.
// - refetchOnWindowFocus: false: 창으로 돌아올 때마다 자동으로 다시 받지 않는다. 데이터는 이 앱에서
//   업로드·FTP 변경을 할 때 바뀌고, 그때 직접 무효화하기 때문이다.
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 30_000, refetchOnWindowFocus: false } },
});

// MUI 테마: 라이트 모드, 주색 파랑 #1565c0, 보조색 주황 #ef6c00, 모서리 8px. 차트(D3)도 같은 색을 직접
// 써서(파워·CTL 선은 파랑, ATL 선은 주황) 화면 색이 맞는다.
const theme = createTheme({
  palette: { mode: "light", primary: { main: "#1565c0" }, secondary: { main: "#ef6c00" } },
  shape: { borderRadius: 8 },
});

// Provider 순서: 테마 → 쿼리 캐시 → 라우터. 모든 화면이 테마와 캐시를 쓰므로 가장 바깥에 둔다.
export default function App() {
  return (
    <ThemeProvider theme={theme}>
      {/* CssBaseline: 브라우저마다 다른 기본 스타일을 MUI 기준으로 맞추고 테마의 배경·글자색을 body에 */}
      {/* 적용한다. */}
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <Routes>
            {/* path 없는 부모 Route: 모든 화면을 Layout(상단 메뉴바) 안의 Outlet 자리에 그린다. '/'는 */}
            {/* 대시보드로 바로 보낸다(replace로 뒤로 가기 기록을 남기지 않음). 목록에 없는 주소에는 그릴 */}
            {/* 화면이 없다(따로 404 화면을 두지 않았다). 배포 시 nginx가 모르는 경로를 index.html로 */}
            {/* 돌려줘(try_files) 새로고침해도 이 라우터가 주소를 해석한다. */}
            <Route element={<Layout />}>
              <Route index element={<Navigate to="/dashboard" replace />} />
              <Route path="/athlete" element={<AthletePage />} />
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/activities" element={<ActivitiesPage />} />
              <Route path="/activities/:id" element={<ActivityPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
