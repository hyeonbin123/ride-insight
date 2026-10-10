import { Alert, Button, Card, CardContent, Grid, LinearProgress, Stack, Typography } from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link as RouterLink, useNavigate, useParams } from "react-router";
import { api } from "../api/client";
import { PowerCurveChart } from "../components/charts/PowerCurveChart";
import { TimeSeriesChart } from "../components/charts/TimeSeriesChart";
import { formatDate, formatDuration, formatNumber, formatWatts, windowLabel } from "../format";
import { useAthleteStore } from "../state/athleteStore";

const WINDOWS = ["5s_critical_power", "1m_critical_power", "5m_critical_power", "20m_critical_power", "60m_critical_power"];

/** One ride: metrics, power/heart-rate trace, and its power curve against the recent best. */
export function ActivityPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const athleteId = useAthleteStore((s) => s.athleteId);

  const activity = useQuery({ queryKey: ["activity", id], queryFn: () => api.getActivity(id) });
  const samples = useQuery({ queryKey: ["samples", id], queryFn: () => api.getSamples(id, 1) });
  const curve = useQuery({ queryKey: ["curve", id], queryFn: () => api.getActivityPowerCurve(id) });
  const asOf = activity.data?.startedAt.slice(0, 10);
  const best = useQuery({
    queryKey: ["athlete-curve", athleteId, 6, asOf],
    queryFn: () => api.getAthletePowerCurve(athleteId!, 6, asOf),
    enabled: !!athleteId && !!asOf,
  });
  const remove = useMutation({
    mutationFn: () => api.deleteActivity(id),
    onSuccess: () => {
      qc.invalidateQueries();
      navigate("/activities");
    },
  });

  if (activity.isLoading) return <LinearProgress />;
  if (activity.isError || !activity.data) return <Alert severity="error">라이딩을 찾을 수 없습니다.</Alert>;
  const a = activity.data;
  const m = a.metrics;

  return (
    <Stack spacing={2}>
      <Stack sx={{ alignItems: "center", justifyContent: "space-between", flexWrap: "wrap" }} direction="row">
        <div>
          <Typography variant="h5">{formatDate(a.startedAt)}</Typography>
          <Typography variant="body2" color="text.secondary">
            {a.sourceName ?? a.source} · {formatDuration(a.durationSecs)} · 샘플 {a.sampleCount}개 · 간격 {a.recIntSecs ?? "-"}s
            {a.intervalShare != null && a.intervalShare < 0.9 ? " (타임스탬프 흔들림)" : ""}
            {a.crcValid === false ? " · CRC 불일치" : ""}
          </Typography>
        </div>
        <Stack direction="row" spacing={1}>
          <Button component={RouterLink} to="/activities">
            목록
          </Button>
          <Button color="error" onClick={() => window.confirm("이 라이딩을 삭제할까요?") && remove.mutate()} disabled={remove.isPending}>
            삭제
          </Button>
        </Stack>
      </Stack>

      <Grid container spacing={2}>
        {[
          { label: "NP", value: formatWatts(m?.normalizedPower) },
          { label: "평균 파워", value: formatWatts(m?.averagePower) },
          { label: "IF", value: formatNumber(m?.intensityFactor, 2) },
          { label: `TSS (FTP ${m?.ftpUsed ?? "-"})`, value: formatNumber(m?.tss, 0) },
          { label: "일 (kJ)", value: formatNumber(m?.workKj, 0) },
          { label: "최대 파워", value: formatWatts(m?.maxPower) },
        ].map((k) => (
          <Grid key={k.label} size={{ xs: 6, sm: 4, md: 2 }}>
            <Card>
              <CardContent sx={{ py: 1.5 }}>
                <Typography variant="caption" color="text.secondary">
                  {k.label}
                </Typography>
                <Typography variant="h6">{k.value}</Typography>
              </CardContent>
            </Card>
          </Grid>
        ))}
      </Grid>
      {!a.hasPower && <Alert severity="info">이 라이딩에는 파워 데이터가 없어 지표를 계산하지 않았습니다.</Alert>}
      {m && !m.ftpUsed && <Alert severity="warning">선수의 FTP가 없어 IF·TSS를 계산하지 못했습니다. 선수·FTP 페이지에서 FTP를 등록하세요.</Alert>}

      <Card>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            파워 · 심박
          </Typography>
          {samples.isLoading && <LinearProgress />}
          {samples.data && (
            <TimeSeriesChart
              secs={samples.data.secs}
              series={[
                ...(samples.data.power ? [{ name: "파워", color: "#1565c0", values: samples.data.power, unit: "W" }] : []),
                ...(samples.data.heartRate ? [{ name: "심박", color: "#d32f2f", values: samples.data.heartRate, unit: "bpm", right: true }] : []),
              ]}
              reference={m?.ftpUsed ? { value: m.ftpUsed, label: `FTP ${m.ftpUsed}` } : undefined}
            />
          )}
        </CardContent>
      </Card>

      {a.hasPower && (
        <Card>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              파워 커브
            </Typography>
            {curve.isLoading && <LinearProgress />}
            {curve.data && <PowerCurveChart points={curve.data.points} comparison={best.data?.points} labels={["이 라이딩", "최근 6주 최고"]} />}
            {m && (
              <Typography variant="body2" color="text.secondary">
                {WINDOWS.filter((k) => m.mmp[k] != null)
                  .map((k) => `${windowLabel(k)} ${formatWatts(m.mmp[k])}`)
                  .join(" · ")}
              </Typography>
            )}
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}
