import { useMemo, useState } from "react";
import {
  Alert,
  Button,
  Card,
  CardContent,
  Grid,
  LinearProgress,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
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
  // Reference date ("as of"). Defaults to today; "마지막 라이딩" jumps to the newest activity so
  // imported or old data still shows a chart.
  const [today] = useState(() => isoDate(new Date()));
  const [asOf, setAsOf] = useState(today);

  const { from, to } = useMemo(() => {
    const end = new Date(asOf + "T00:00:00Z");
    return { to: asOf, from: isoDate(new Date(end.getTime() - days * 86_400_000)) };
  }, [days, asOf]);
  const latest = useQuery({
    queryKey: ["activities", athleteId, "latest"],
    queryFn: () => api.listActivities(athleteId!, 1),
    enabled: !!athleteId,
  });
  const load = useQuery({
    queryKey: ["training-load", athleteId, from, to],
    queryFn: () => api.getTrainingLoad(athleteId!, from, to),
    enabled: !!athleteId,
  });
  const curve = useQuery({
    queryKey: ["athlete-curve", athleteId, weeks, asOf],
    queryFn: () => api.getAthletePowerCurve(athleteId!, weeks, asOf),
    enabled: !!athleteId,
  });
  const estimate = useQuery({
    queryKey: ["ftp-estimate", athleteId, weeks, asOf],
    queryFn: () => api.getFtpEstimate(athleteId!, weeks, asOf),
    enabled: !!athleteId,
  });

  if (!athleteId) return <NeedAthlete />;
  const last = load.data?.days.at(-1);
  const latestDate = latest.data?.items[0]?.startedAt.slice(0, 10);

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }}>
        <TextField
          type="date"
          size="small"
          label="기준일"
          value={asOf}
          onChange={(e) => e.target.value && setAsOf(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <Button size="small" disabled={!latestDate || latestDate === asOf} onClick={() => latestDate && setAsOf(latestDate)}>
          마지막 라이딩 기준{latestDate ? ` (${latestDate})` : ""}
        </Button>
        <Button size="small" disabled={asOf === today} onClick={() => setAsOf(today)}>
          오늘 기준
        </Button>
      </Stack>
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
          <Typography variant="h6" gutterBottom>
            FTP 추정 ({weeks}주 기준)
          </Typography>
          {estimate.isLoading && <LinearProgress />}
          {estimate.isError && <Alert severity="error">{String(estimate.error)}</Alert>}
          {estimate.data && (
            <Stack direction="row" spacing={3} sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
              <Typography variant="h4">{formatWatts(estimate.data.ftpWatts)}</Typography>
              <Typography variant="body2" color="text.secondary">
                방법 {estimate.data.method} · 라이딩 {estimate.data.ridesInWindow}개 · 20분 최대 {formatWatts(estimate.data.best20m)} · 60분 최대 {formatWatts(estimate.data.best60m)}
                {estimate.data.rules["R3_critical_power"] != null ? ` · CP 모델 ${formatWatts(estimate.data.rules["R3_critical_power"])}` : ""}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ flexBasis: "100%" }}>
                {estimate.data.note}
              </Typography>
            </Stack>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between", mb: 1 }}>
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
          <Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between", mb: 1 }}>
            <Typography variant="h6">최근 최고 파워 커브</Typography>
            <ToggleButtonGroup size="small" exclusive value={weeks} onChange={(_, v) => v && setWeeks(v)}>
              <ToggleButton value={6}>6주</ToggleButton>
              <ToggleButton value={12}>12주</ToggleButton>
              <ToggleButton value={52}>52주</ToggleButton>
            </ToggleButtonGroup>
          </Stack>
          {curve.isLoading && <LinearProgress />}
          {curve.isError && <Alert severity="error">{String(curve.error)}</Alert>}
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
