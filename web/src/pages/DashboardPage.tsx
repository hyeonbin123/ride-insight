import { useMemo, useState } from "react";
import { Alert, Card, CardContent, Grid, LinearProgress, Stack, ToggleButton, ToggleButtonGroup, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { PowerCurveChart } from "../components/charts/PowerCurveChart";
import { TrainingLoadChart } from "../components/charts/TrainingLoadChart";
import { NeedAthlete } from "../components/NeedAthlete";
import { formatNumber, formatWatts, isoDate, windowLabel } from "../format";
import { useAthleteStore } from "../state/athleteStore";

/** Training load (PMC) and the recent best power curve. */
export function DashboardPage() {
  const athleteId = useAthleteStore((s) => s.athleteId);
  const [days, setDays] = useState(90);
  const [weeks, setWeeks] = useState(6);

  // "Today" is fixed when the page mounts; the range follows the selected number of days.
  const [now] = useState(() => Date.now());
  const { from, to } = useMemo(
    () => ({ to: isoDate(new Date(now)), from: isoDate(new Date(now - days * 86_400_000)) }),
    [days, now],
  );
  const load = useQuery({
    queryKey: ["training-load", athleteId, from, to],
    queryFn: () => api.getTrainingLoad(athleteId!, from, to),
    enabled: !!athleteId,
  });
  const curve = useQuery({
    queryKey: ["athlete-curve", athleteId, weeks],
    queryFn: () => api.getAthletePowerCurve(athleteId!, weeks),
    enabled: !!athleteId,
  });

  if (!athleteId) return <NeedAthlete />;
  const last = load.data?.days.at(-1);

  return (
    <Stack spacing={2}>
      <Grid container spacing={2}>
        {[
          { label: "CTL (체력)", value: formatNumber(last?.ctl, 1) },
          { label: "ATL (피로)", value: formatNumber(last?.atl, 1) },
          { label: "TSB (컨디션)", value: formatNumber(last?.tsb, 1) },
          { label: `${weeks}주 20분 최대`, value: formatWatts(curve.data?.standard["20m_critical_power"]) },
        ].map((k) => (
          <Grid key={k.label} size={{ xs: 6, md: 3 }}>
            <Card>
              <CardContent>
                <Typography variant="caption" color="text.secondary">
                  {k.label}
                </Typography>
                <Typography variant="h5">{k.value}</Typography>
              </CardContent>
            </Card>
          </Grid>
        ))}
      </Grid>

      <Card>
        <CardContent>
          <Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between",  mb: 1 }}>
            <Typography variant="h6">훈련 부하 (PMC)</Typography>
            <ToggleButtonGroup size="small" exclusive value={days} onChange={(_, v) => v && setDays(v)}>
              <ToggleButton value={42}>6주</ToggleButton>
              <ToggleButton value={90}>90일</ToggleButton>
              <ToggleButton value={180}>180일</ToggleButton>
              <ToggleButton value={365}>1년</ToggleButton>
            </ToggleButtonGroup>
          </Stack>
          {load.isLoading && <LinearProgress />}
          {load.isError && <Alert severity="error">{String(load.error)}</Alert>}
          {load.data && <TrainingLoadChart days={load.data.days} />}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between",  mb: 1 }}>
            <Typography variant="h6">최근 최고 파워 커브</Typography>
            <ToggleButtonGroup size="small" exclusive value={weeks} onChange={(_, v) => v && setWeeks(v)}>
              <ToggleButton value={6}>6주</ToggleButton>
              <ToggleButton value={12}>12주</ToggleButton>
              <ToggleButton value={52}>52주</ToggleButton>
            </ToggleButtonGroup>
          </Stack>
          {curve.isLoading && <LinearProgress />}
          {curve.data && curve.data.points.length > 0 ? (
            <>
              <PowerCurveChart points={curve.data.points} labels={[`${weeks}주 최고`, ""]} />
              <Typography variant="body2" color="text.secondary">
                {Object.entries(curve.data.standard)
                  .filter(([, v]) => v != null)
                  .map(([k, v]) => `${windowLabel(k)} ${formatWatts(v)}`)
                  .join(" · ")}
              </Typography>
            </>
          ) : (
            !curve.isLoading && <Typography color="text.secondary">이 기간에 파워 데이터가 있는 라이딩이 없습니다.</Typography>
          )}
        </CardContent>
      </Card>
    </Stack>
  );
}
