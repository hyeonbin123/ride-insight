// [파일 역할] 대시보드(/dashboard, 첫 화면). 기준일을 정하면 그날 기준의 훈련 부하 요약(CTL·ATL·TSB), 기간
// 20분 최대, FTP 추정 카드, 훈련 부하 그래프(PMC), 기간 최고 파워 커브를 보여 준다.
// [데이터 흐름] 서버 API 네 개를 React Query로 동시에 부른다: 최신 활동 1개(마지막 라이딩 날짜), 훈련
// 부하(from~to), 기간 최고 파워 커브(weeks, asOf), FTP 추정(weeks, asOf). 기준일·기간을 바꾸면 queryKey가
// 바뀌어 새로 받고, 전에 본 조합은 캐시에서 바로 나온다.

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
  // 화면 상태: PMC 기간(days, 기본 90일), 파워 커브·FTP 추정 기간(weeks, 기본 6주, 3단계 규칙의 창 크기와
  // 같다), 기준일(asOf).
  const [days, setDays] = useState(90);
  const [weeks, setWeeks] = useState(6);
  // Reference date ("as of"). Defaults to today; "마지막 라이딩" jumps to the newest activity so
  // imported or old data still shows a chart.
  // 오늘 날짜는 처음 렌더 때 한 번만 계산해 고정한다(UTC 날짜, 서버도 날짜를 UTC로 센다). '마지막 라이딩
  // 기준' 버튼은 OpenData에서 가져온 예전 데이터처럼 최근 라이딩이 없을 때도 그래프가 비지 않게 하려고
  // 넣었다(커밋 c3546ab).
  const [today] = useState(() => isoDate(new Date()));
  const [asOf, setAsOf] = useState(today);

  // PMC 조회 범위: to = 기준일, from = 기준일 − days일. 날짜를 UTC 자정으로 해석해 하루 단위(86,400,000 ms)로
  // 빼므로 시간대에 따라 날짜가 밀리지 않는다. 훈련 부하 워밍업(첫 활동부터 누적)은 서버가 하므로 from 이전
  // 기록도 CTL에 반영된다.
  const { from, to } = useMemo(() => {
    const end = new Date(asOf + "T00:00:00Z");
    return { to: asOf, from: isoDate(new Date(end.getTime() - days * 86_400_000)) };
  }, [days, asOf]);
  // 최신 활동 1개만 받아 '마지막 라이딩' 날짜를 얻는다. queryKey가 ['activities', id, ...]로 시작해 업로드
  // 때의 목록 무효화(['activities', id])에 함께 걸린다.
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
  // FTP 추정: 서버는 규칙 R1(20분 최대 × 0.95)을 돌려주고 R2·R3 값과 측정 오차를 note로 함께 준다(3단계에서
  // 모델 채택 기준을 확정하지 못해 규칙을 쓴다). 업로드 화면의 무효화 목록에 'ftp-estimate'는 없어, 업로드
  // 직후에는 이 카드가 staleTime(30초) 동안 이전 값을 보일 수 있다.
  const estimate = useQuery({
    queryKey: ["ftp-estimate", athleteId, weeks, asOf],
    queryFn: () => api.getFtpEstimate(athleteId!, weeks, asOf),
    enabled: !!athleteId,
  });

  // 선수가 없으면 안내만 보여 준다(모든 훅은 이 줄 위에서 호출해 훅 순서를 일정하게 유지한다). last는 PMC의
  // 마지막 날(=기준일) 값, latestDate는 최신 활동의 날짜 부분이다.
  if (!athleteId) return <NeedAthlete />;
  const last = load.data?.days.at(-1);
  const latestDate = latest.data?.items[0]?.startedAt.slice(0, 10);

  return (
    <Stack spacing={2}>
      {/* 기준일 선택 줄: 날짜 입력(빈 값은 무시), '마지막 라이딩 기준', '오늘 기준' 버튼. 이미 그 날짜면 */}
      {/* 버튼을 막는다. */}
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
        {/* 요약 카드 4개: 기준일의 CTL(42일, 체력)·ATL(7일, 피로)·TSB(컨디션)와 기간 20분 최대 파워. */}
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
          {/* 추정값(크게), 근거(방법, 기간 안 파워 라이딩 수, 20분·60분 최대, CP 모델 값), 서버가 준 안내 */}
          {/* 문장(note: 규칙 정의와 측정 오차). 20분 이상 라이딩이 없으면 추정값은 '-'이고 note가 그 */}
          {/* 이유를 말한다. */}
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
            {/* PMC 기간 선택. exclusive 토글은 이미 선택된 버튼을 다시 누르면 null을 주므로 v가 있을 때만 */}
            {/* 바꾼다(선택이 비지 않게). */}
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
            {/* 파워 커브·FTP 추정 기간 선택(6·12·52주). 바꾸면 커브와 추정이 함께 다시 계산된다. */}
            <ToggleButtonGroup size="small" exclusive value={weeks} onChange={(_, v) => v && setWeeks(v)}>
              <ToggleButton value={6}>6주</ToggleButton>
              <ToggleButton value={12}>12주</ToggleButton>
              <ToggleButton value={52}>52주</ToggleButton>
            </ToggleButtonGroup>
          </Stack>
          {curve.isLoading && <LinearProgress />}
          {curve.isError && <Alert severity="error">{String(curve.error)}</Alert>}
          {/* 커브 점이 있으면 곡선과 16구간 값 목록을, 없으면 '파워 라이딩 없음' 안내를 보여 준다. 요청이 */}
          {/* 실패한 경우에는 위의 오류 상자와 이 안내가 함께 보인다. */}
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
