// [파일 역할] 모든 화면의 공통 틀. 위쪽 메뉴바(앱 이름, 화면 이동 버튼 3개, 지금 고른 선수와 FTP)와 그 아래
// 본문 자리(Outlet)를 그린다. App.tsx의 부모 Route가 이 컴포넌트를 쓴다.

import { AppBar, Box, Button, Container, Toolbar, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Link as RouterLink, Outlet, useLocation } from "react-router";
import { api } from "../api/client";
import { useAthleteStore } from "../state/athleteStore";

// 메뉴 항목: 주소와 버튼 글자. 라이딩 상세(/activities/:id)는 목록 버튼 아래에 속한다.
const NAV = [
  { to: "/dashboard", label: "대시보드" },
  { to: "/activities", label: "라이딩" },
  { to: "/athlete", label: "선수·FTP" },
];

// 선택된 선수 id는 Zustand 저장소에서 읽고, 선수 정보는 React Query로 받는다. queryKey ['athlete', id]는 선수
// 페이지와 같아서 한쪽이 받은 데이터를 다른 쪽이 캐시로 같이 쓴다. 선수를 고르지 않았으면(enabled: false)
// 요청하지 않는다. useLocation은 현재 주소에 맞는 메뉴를 굵게 표시하는 데 쓴다.
export function Layout() {
  const athleteId = useAthleteStore((s) => s.athleteId);
  const location = useLocation();
  const athlete = useQuery({
    queryKey: ["athlete", athleteId],
    queryFn: () => api.getAthlete(athleteId!),
    enabled: !!athleteId,
  });

  return (
    <Box sx={{ minHeight: "100vh", bgcolor: "#f5f7fa" }}>
      <AppBar position="static" elevation={0}>
        <Toolbar sx={{ gap: 1 }}>
          {/* 앱 이름을 라우터 링크로 렌더링해 누르면 대시보드로 간다(component prop으로 MUI 스타일 + */}
          {/* react-router 이동을 함께 쓴다). */}
          <Typography variant="h6" component={RouterLink} to="/dashboard" sx={{ color: "inherit", textDecoration: "none", mr: 2 }}>
            ride-insight
          </Typography>
          {/* 메뉴 버튼들. 현재 주소가 메뉴 경로로 시작하면(상세 화면 포함) 불투명·굵게 표시해 지금 위치를 */}
          {/* 알려 준다. 버튼을 라우터 링크로 렌더링해(component={RouterLink}) 페이지를 새로 읽지 않고 */}
          {/* 화면만 바꾼다. */}
          {NAV.map((n) => (
            <Button
              key={n.to}
              component={RouterLink}
              to={n.to}
              color="inherit"
              sx={{ opacity: location.pathname.startsWith(n.to) ? 1 : 0.75, fontWeight: location.pathname.startsWith(n.to) ? 700 : 400 }}
            >
              {n.label}
            </Button>
          ))}
          {/* 남는 가로 공간을 채워 선수 표시를 오른쪽 끝으로 민다. */}
          <Box sx={{ flex: 1 }} />
          <Typography variant="body2" sx={{ opacity: 0.9 }}>
            {athlete.data ? `${athlete.data.name} · FTP ${athlete.data.currentThreshold?.ftpWatts ?? "-"} W` : "선수 미선택"}
          </Typography>
        </Toolbar>
      </AppBar>
      {/* 본문: 현재 주소에 맞는 화면이 Outlet 자리에 그려진다. */}
      <Container maxWidth="lg" sx={{ py: 3 }}>
        <Outlet />
      </Container>
    </Box>
  );
}
