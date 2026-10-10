import { AppBar, Box, Button, Container, Toolbar, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Link as RouterLink, Outlet, useLocation } from "react-router";
import { api } from "../api/client";
import { useAthleteStore } from "../state/athleteStore";

const NAV = [
  { to: "/dashboard", label: "대시보드" },
  { to: "/activities", label: "라이딩" },
  { to: "/athlete", label: "선수·FTP" },
];

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
          <Typography variant="h6" component={RouterLink} to="/dashboard" sx={{ color: "inherit", textDecoration: "none", mr: 2 }}>
            ride-insight
          </Typography>
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
          <Box sx={{ flex: 1 }} />
          <Typography variant="body2" sx={{ opacity: 0.9 }}>
            {athlete.data ? `${athlete.data.name} · FTP ${athlete.data.currentThreshold?.ftpWatts ?? "-"} W` : "선수 미선택"}
          </Typography>
        </Toolbar>
      </AppBar>
      <Container maxWidth="lg" sx={{ py: 3 }}>
        <Outlet />
      </Container>
    </Box>
  );
}
