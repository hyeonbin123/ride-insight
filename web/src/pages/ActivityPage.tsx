// [파일 역할] 라이딩 상세 화면(/activities/:id). 머리글(시작 시각, 출처, 길이, 샘플 수, 기록 간격, 흔들림·CRC
// 경고), 지표 카드 6개(NP, 평균 파워, IF, TSS, 일, 최대 파워), 파워·심박 시계열 그래프(FTP 기준선), 이
// 라이딩의 파워 커브와 최근 6주 최고 커브 비교를 보여 준다. 삭제 버튼도 있고, 삭제가 실패하면 이유를 머리글 밑에 보여 준다.
// [데이터 흐름] 활동 요약·지표(GET /activities/{id}), 주인 선수(GET /athletes/{owner}: 시간대를 알기 위해. 메뉴바·대시보드와
// 같은 캐시 키 ['athlete', id]), 시계열(.../samples?every=1), 활동 파워 커브(.../power-curve), 비교용 기간 최고 커브(GET
// /athletes/{owner}/power-curve?weeks=6&asOf=라이딩 시작 시각의 선수 시간대 현지 날짜)를 각각 React Query로 받는다.

import { Alert, Button, Card, CardContent, Grid, LinearProgress, Stack, Typography } from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link as RouterLink, useNavigate, useParams } from "react-router";
import { api } from "../api/client";
import { PowerCurveChart } from "../components/charts/PowerCurveChart";
import { TimeSeriesChart } from "../components/charts/TimeSeriesChart";
import { dateIn, describeError, formatDate, formatDuration, formatNumber, formatWatts, windowLabel } from "../format";

// 파워 커브 아래 글자로 보여 줄 대표 구간 5개(5초, 1분, 5분, 20분, 60분). 키는 서버 MMP 응답의 GoldenCheetah
// 이름이다.
const WINDOWS = ["5s_critical_power", "1m_critical_power", "5m_critical_power", "20m_critical_power", "60m_critical_power"];

/**
 * 라이딩 하나: 지표, 파워·심박 그래프, 최근 최고 기록과 비교한 파워 커브.
 * 주소의 :id로 활동을 찾는다. 시계열·커브 요청은 활동 요약과 동시에 나가고, 주인 선수 조회는 활동 요약(소유 선수
 * id)이 온 뒤에, 비교 커브는 주인 선수 조회가 끝난 뒤(성공이면 그 시간대, 실패면 UTC 기준)에 나간다(enabled 조건).
 */
export function ActivityPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const activity = useQuery({ queryKey: ["activity", id], queryFn: () => api.getActivity(id) });
  // 비교 커브는 메뉴바에서 고른 선수가 아니라 이 라이딩의 주인 선수의 것이다. 주인 선수 정보는 시간대(timeZone) 때문에
  // 받는다: 비교 기준일과 머리글의 시작 시각을 그 시간대로 센다(2026-10-24). 캐시 키가 메뉴바·대시보드와 같아 그 선수를
  // 고른 상태면 요청 없이 캐시에서 온다.
  const athleteId = activity.data?.athleteId;
  const athlete = useQuery({
    queryKey: ["athlete", athleteId],
    queryFn: () => api.getAthlete(athleteId!),
    enabled: !!athleteId,
  });
  const timeZone = athlete.data?.timeZone;
  // 시계열은 every=1(가능한 최고 해상도)로 요청한다. 긴 라이딩은 서버가 20,000점을 넘지 않도록 간격을 키워
  // 돌려준다.
  const samples = useQuery({ queryKey: ["samples", id], queryFn: () => api.getSamples(id, 1) });
  const curve = useQuery({ queryKey: ["curve", id], queryFn: () => api.getActivityPowerCurve(id) });
  // 비교 기준일 = 라이딩 시작 시각을 주인 선수의 시간대로 센 현지 날짜(dateIn). 서버는 asOf를 선수 시간대의 현지 날짜로
  // 읽어 창을 '[그 다음 날 0시 − 6주, 그 다음 날 0시)'로 잡으므로, 같은 기준으로 세야 이 라이딩이 비교 곡선(점선)에
  // 들어간다. 2026-10-24 전에는 서버가 주는 UTC 시각의 앞 10자(UTC 날짜)를 썼다. 그러면 한국 시각 00:00~08:59에 시작한
  // 라이딩은 asOf가 전날이 되어 창이 그 라이딩 전에 끝나, '최근 6주 최고'가 이 라이딩을 포함하지 않았다(대시보드는
  // 2026-10-15부터 dateIn을 썼지만 이 화면은 남아 있었다).
  // 주인 선수 조회가 끝나기 전(성공도 실패도 아님)에는 기준일을 정하지 않고 기다린다: UTC 날짜로 먼저 요청하면 요청이 하나
  // 더 나가고 점선이 한 번 바뀌어 보인다. 조회가 끝나면 성공이든 실패든 기준일을 정한다. 실패했거나 응답에 시간대가
  // 없으면(timeZone 필드가 생기기 전의 서버) dateIn이 UTC 날짜로 대신해 2026-10-24 전과 같은 기준일이 된다. '시간대가 올
  // 때까지' 기다리면 시간대가 없는 응답에서는 비교 곡선이 끝내 나오지 않는다. 브라우저가 모르는 시간대 이름도 dateIn이
  // UTC 날짜로 대신한다. 업로드 화면이 같은 선수의 커브 캐시(['athlete-curve', id])를 무효화하므로 새 라이딩이 반영된다.
  const startedAt = activity.data?.startedAt;
  const ownerSettled = athlete.isSuccess || athlete.isError;
  const asOf = startedAt && ownerSettled ? dateIn(new Date(startedAt), timeZone) : undefined;
  const best = useQuery({
    queryKey: ["athlete-curve", athleteId, 6, asOf],
    queryFn: () => api.getAthletePowerCurve(athleteId!, 6, asOf),
    enabled: !!athleteId && !!asOf,
  });
  // 삭제: DELETE 성공 뒤 캐시 전체를 무효화하고(목록·부하·커브가 모두 바뀌므로) 목록으로 돌아간다. 실패하면 이 화면에
  // 남아 아래 머리글 밑에 이유를 보여 준다(2026-10-24 추가: 같은 선수의 업로드·FTP·시간대 변경이 선수 행 잠금을 쥐고 있으면
  // 서버가 409 athlete_busy + Retry-After로 답한다. 그 전에는 실패를 어디에도 보이지 않았다).
  const remove = useMutation({
    mutationFn: () => api.deleteActivity(id),
    onSuccess: () => {
      qc.invalidateQueries();
      navigate("/activities");
    },
  });

  // 활동 요약이 없으면 나머지를 그릴 수 없으므로 로딩 막대 또는 오류(없는 id면 404)만 보여 준다. m(지표)은
  // 파워가 없는 활동이면 undefined다.
  if (activity.isLoading) return <LinearProgress />;
  if (activity.isError || !activity.data) return <Alert severity="error">라이딩을 찾을 수 없습니다.</Alert>;
  const a = activity.data;
  const m = a.metrics;

  return (
    <Stack spacing={2}>
      <Stack sx={{ alignItems: "center", justifyContent: "space-between", flexWrap: "wrap" }} direction="row">
        <div>
          {/* 시작 시각은 주인 선수의 시간대로 보인다(선수 정보를 받기 전에는 브라우저 시간대). */}
          <Typography variant="h5">{formatDate(a.startedAt, timeZone)}</Typography>
          {/* 부제: 출처 파일 이름(없으면 출처 종류), 길이, 샘플 수, 추정 기록 간격(소수 둘째 자리까지. DB의 real 열 값이 */}
          {/* 1.2599999904632568처럼 올 수 있어 다른 지표처럼 반올림한다, 2026-10-24). 최빈 간격 비율이 0.9 미만이면 */}
          {/* '타임스탬프 흔들림'을 덧붙인다(1단계 규칙의 흔들림 기준과 같은 0.9). CRC가 틀렸지만 저장된 FIT는 */}
          {/* 'CRC 불일치'로 표시한다(5단계 H8: 거절하지 않고 표시). */}
          <Typography variant="body2" color="text.secondary">
            {a.sourceName ?? a.source} · {formatDuration(a.durationSecs)} · 샘플 {a.sampleCount}개 · 간격 {formatNumber(a.recIntSecs, 2)}s
            {a.intervalShare != null && a.intervalShare < 0.9 ? " (타임스탬프 흔들림)" : ""}
            {a.crcValid === false ? " · CRC 불일치" : ""}
          </Typography>
        </div>
        <Stack direction="row" spacing={1}>
          <Button component={RouterLink} to="/activities">
            목록
          </Button>
          {/* 브라우저 확인 창에서 '확인'을 눌렀을 때만 삭제 요청을 보낸다(&& 단축 평가). */}
          <Button color="error" onClick={() => window.confirm("이 라이딩을 삭제할까요?") && remove.mutate()} disabled={remove.isPending}>
            삭제
          </Button>
        </Stack>
      </Stack>
      {/* 삭제 실패: 코드별 한국어 설명과 서버 원문(format.ts의 describeError). 409 athlete_busy면 Retry-After 초('5초 뒤 다시 */}
      {/* 시도하세요')가 붙는다. 다시 누르면(새 mutate) 사라진다. */}
      {remove.isError && <Alert severity="error">{describeError(remove.error)}</Alert>}

      <Grid container spacing={2}>
        {/* 지표 카드: 배열로 정의해 map으로 같은 모양의 카드를 그린다. TSS 라벨에 계산에 쓴 FTP를 함께 */}
        {/* 적어 어떤 FTP 기준 값인지 보이게 한다. 값이 없으면 '-'. */}
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
      {/* 파워가 없는 활동(서버는 저장하되 지표 없음)과 FTP가 없어 IF·TSS를 못 구한 활동은 이유를 알려 */}
      {/* 주고, 후자는 FTP 등록 화면으로 안내한다. */}
      {!a.hasPower && <Alert severity="info">이 라이딩에는 파워 데이터가 없어 지표를 계산하지 않았습니다.</Alert>}
      {m && !m.ftpUsed && <Alert severity="warning">선수의 FTP가 없어 IF·TSS를 계산하지 못했습니다. 선수·FTP 페이지에서 FTP를 등록하세요.</Alert>}

      <Card>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            파워 · 심박
          </Typography>
          {samples.isLoading && <LinearProgress />}
          {/* 있는 시리즈만 그린다: 파워는 왼쪽 축(파랑), 심박은 오른쪽 축(빨강). FTP가 있으면 왼쪽 축에 */}
          {/* 점선 기준선을 긋는다. */}
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

      {/* 파워 커브 카드: 이 라이딩 곡선(실선)과 최근 6주 최고 곡선(점선)을 겹쳐 그리고, 아래에 대표 구간 */}
      {/* 값을 글자로 적는다. 파워가 없는 활동은 카드 자체를 그리지 않는다. */}
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
