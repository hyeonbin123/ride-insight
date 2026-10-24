// [파일 역할] 라이딩 화면(/activities). 위 카드에서 FIT 파일을 여러 개 올리고 결과를 줄마다 보여 주며, 아래
// 카드에서 선수의 라이딩 목록(최신순, '더 보기' 페이지네이션)을 표로 보여 준다. 첫 열(시작 시각)의 링크나 행
// 클릭으로 상세 화면에 간다.
// [데이터 흐름] 업로드는 POST /athletes/{id}/activities(서버가 디코딩·종목 확인·지표 계산까지 하고 요약을
// 돌려줌), 목록은 GET .../activities?limit&before(커서 방식), 선수 시간대는 GET /athletes/{id}(메뉴바와 같은 캐시).

import { useRef, useState } from "react";
import {
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
  LinearProgress,
  Link,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link as RouterLink, useNavigate } from "react-router";
import { api, ApiRequestError } from "../api/client";
import type { Activity } from "../api/types";
import { describeError, formatDate, formatDuration, formatNumber, formatWatts } from "../format";
import { useAthleteStore } from "../state/athleteStore";
import { NeedAthlete } from "../components/NeedAthlete";

// 목록을 한 번에 30개씩 받는다(서버 기본 50, 최대 200).
const PAGE = 30;

/** FIT 업로드와 페이지 단위로 나눠 받는 활동 목록. */
export function ActivitiesPage() {
  const athleteId = useAthleteStore((s) => s.athleteId);
  const qc = useQueryClient();
  const navigate = useNavigate();
  const fileInput = useRef<HTMLInputElement>(null);
  const [uploadLog, setUploadLog] = useState<{ name: string; ok: boolean; text: string }[]>([]);

  // 선수 정보는 시간대(timeZone) 때문에 받는다: 목록의 시작 시각을 선수 시간대로 보여 PMC·FTP 적용일의 날짜와
  // 맞춘다(2026-10-24, 그 전에는 브라우저 시간대). 캐시 키가 메뉴바·대시보드와 같아 보통은 요청 없이 캐시에서 온다. 받기
  // 전에는 브라우저 시간대로 보인다.
  const athlete = useQuery({
    queryKey: ["athlete", athleteId],
    queryFn: () => api.getAthlete(athleteId!),
    enabled: !!athleteId,
  });
  const timeZone = athlete.data?.timeZone;

  // 커서 페이지네이션 목록. 첫 페이지는 before 없이 받고, 다음 페이지는 직전 응답의 nextBefore(마지막 항목의
  // 시작 시각)를 before로 넘긴다. nextBefore가 없으면 hasNextPage가 false가 돼 '더 보기'가 사라진다. 오프셋
  // 방식과 달리 중간에 업로드가 생겨도 항목이 겹치거나 빠지지 않는다(서버에서 같은 선수의 시작 시각은 유일).
  const list = useInfiniteQuery({
    queryKey: ["activities", athleteId],
    queryFn: ({ pageParam }) => api.listActivities(athleteId!, PAGE, pageParam ?? undefined),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextBefore ?? undefined,
    enabled: !!athleteId,
  });

  // 여러 파일 업로드. 파일을 하나씩 차례로 올리고(병렬이 아니라 순서대로), 한 파일이 실패해도 예외를 밖으로
  // 던지지 않고 결과 목록에 남긴 뒤 다음 파일로 넘어간다. 그래서 mutation 자체는 항상 성공하고 파일별
  // 성공·실패가 uploadLog에 쌓인다.
  // 실패 메시지는 상태 코드 + describeError(format.ts)의 문장: 코드별 한국어 설명과 서버 원문(예: 422 unsupported_sport,
  // 400 invalid_fit, 409 duplicate_activity, 413 file_too_large). 429 too_many_uploads(서버 전체 동시 업로드 상한)와
  // 409 athlete_busy(같은 선수의 다른 쓰기 요청이 잠금을 쥠)에는 Retry-After 초('5초 뒤 다시 시도하세요')가
  // 붙는다(2026-10-24). 자동 재시도는 하지 않는다: 파일을 순서대로 올려 혼자서는 두 응답이 드물고, 사용자가 그 파일을
  // 다시 고르면 된다.
  const upload = useMutation({
    mutationFn: async (files: File[]) => {
      const results: { name: string; ok: boolean; text: string }[] = [];
      for (const f of files) {
        try {
          const a = await api.uploadActivity(athleteId!, f);
          results.push({
            name: f.name,
            ok: true,
            text: a.metrics ? `저장됨 · NP ${formatWatts(a.metrics.normalizedPower)} · TSS ${formatNumber(a.metrics.tss, 0)}` : "저장됨 (파워 없음)",
          });
        } catch (e) {
          results.push({ name: f.name, ok: false, text: e instanceof ApiRequestError ? `${e.status} ${describeError(e)}` : describeError(e) });
        }
      }
      return results;
    },
    onSuccess: (results) => {
      // 새 결과를 위에 붙이고 최근 20줄만 남긴다. 그다음 이 선수의 목록, 훈련 부하, 기간 파워 커브, FTP 추정
      // 캐시를 무효화해 새 라이딩이 반영되게 한다. FTP 추정은 처음에 이 목록에서 빠져 있어, 업로드 직후
      // 대시보드의 추정 카드가 staleTime(30초) 동안 이전 값을 보였다(2026-10-13 수정).
      setUploadLog((prev) => [...results, ...prev].slice(0, 20));
      qc.invalidateQueries({ queryKey: ["activities", athleteId] });
      qc.invalidateQueries({ queryKey: ["training-load", athleteId] });
      qc.invalidateQueries({ queryKey: ["athlete-curve", athleteId] });
      qc.invalidateQueries({ queryKey: ["ftp-estimate", athleteId] });
    },
  });

  // 선수를 고르지 않았으면 안내만 보여 준다. 훅 호출(위의 useInfiniteQuery 등)은 이 return보다 앞에 있어야
  // 렌더마다 훅 순서가 같다는 React 규칙을 지킨다. 받은 페이지들은 하나의 배열로 펼쳐 표에 쓴다.
  if (!athleteId) return <NeedAthlete />;
  const items: Activity[] = list.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <Stack sx={{ alignItems: "center", flexWrap: "wrap" }} direction="row" spacing={2}>
            <Typography variant="h6">FIT 파일 올리기</Typography>
            {/* 숨긴 파일 입력. 버튼이 click()으로 대신 열어 MUI 버튼 모양을 쓴다. 선택 후 value를 비워 */}
            {/* 같은 파일을 다시 골라도 onChange가 다시 일어나게 한다(중복 업로드 409 확인 등). */}
            <input
              ref={fileInput}
              type="file"
              accept=".fit"
              multiple
              hidden
              onChange={(e) => {
                const files = Array.from(e.target.files ?? []);
                if (files.length) upload.mutate(files);
                e.target.value = "";
              }}
            />
            <Button variant="contained" onClick={() => fileInput.current?.click()} disabled={upload.isPending}>
              파일 선택
            </Button>
            {/* 서버 규칙 안내: 러닝·수영·걷기·하이킹은 422로 거절되고, 10 MB를 넘으면 413(docs/api.md). */}
            <Typography variant="body2" color="text.secondary">
              사이클링 활동만 저장됩니다(러닝 등은 422). 파일당 10 MB까지.
            </Typography>
          </Stack>
          {upload.isPending && <LinearProgress sx={{ mt: 1 }} />}
          {uploadLog.length > 0 && (
            <Stack spacing={0.5} sx={{ mt: 1 }}>
              {uploadLog.map((l, i) => (
                <Alert key={`${l.name}-${i}`} severity={l.ok ? "success" : "error"} sx={{ py: 0 }}>
                  <strong>{l.name}</strong> — {l.text}
                </Alert>
              ))}
            </Stack>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          {/* 제목 옆 개수: 받은 라이딩 수이고, 다음 페이지가 남아 있으면 '+'를 붙인다. */}
          <Typography variant="h6" gutterBottom>
            라이딩 {items.length ? `(${items.length}${list.hasNextPage ? "+" : ""})` : ""}
          </Typography>
          {list.isLoading && <LinearProgress />}
          {list.isError && <Alert severity="error">{describeError(list.error)}</Alert>}
          {!list.isLoading && items.length === 0 && <Typography color="text.secondary">아직 라이딩이 없습니다. FIT 파일을 올려 보세요.</Typography>}
          {items.length > 0 && (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>시작</TableCell>
                  <TableCell>시간</TableCell>
                  <TableCell align="right">평균 W</TableCell>
                  <TableCell align="right">NP</TableCell>
                  <TableCell align="right">IF</TableCell>
                  <TableCell align="right">TSS</TableCell>
                  <TableCell align="right">20분 최대</TableCell>
                  <TableCell>출처</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {/* 첫 열(시작 시각)은 실제 링크여서 Tab으로 포커스하고 Enter로 상세에 간다(2026-10-24, 키보드·화면 읽기 */}
                {/* 프로그램의 접근 경로. 그 전에는 행 onClick만 있어 마우스로만 갈 수 있었다). 행 전체를 a로 두면 */}
                {/* 'tbody 안의 a' 경고가 나서(커밋 c3546ab) 셀 안에 MUI Link를 둔다. 행 onClick은 마우스로 다른 셀을 눌렀을 */}
                {/* 때의 보조 경로다. 시작 시각은 선수 시간대로 보이고, 값이 없는 지표는 format 함수가 '-'로 표시한다. */}
                {items.map((a) => (
                  <TableRow
                    key={a.id}
                    hover
                    sx={{ cursor: "pointer" }}
                    onClick={(e) => {
                      // 링크에서 난 클릭은 링크(react-router)와 브라우저가 처리한다(Ctrl+클릭으로 새 탭 열기 포함). 여기서도
                      // navigate를 부르면 같은 주소가 두 번 쌓이므로, 링크 밖의 셀을 눌렀을 때만 이동한다.
                      if ((e.target as Element).closest("a")) return;
                      navigate(`/activities/${a.id}`);
                    }}
                  >
                    <TableCell>
                      <Link component={RouterLink} to={`/activities/${a.id}`}>
                        {formatDate(a.startedAt, timeZone)}
                      </Link>
                    </TableCell>
                    <TableCell>{formatDuration(a.durationSecs)}</TableCell>
                    <TableCell align="right">{formatWatts(a.metrics?.averagePower)}</TableCell>
                    <TableCell align="right">{formatWatts(a.metrics?.normalizedPower)}</TableCell>
                    <TableCell align="right">{formatNumber(a.metrics?.intensityFactor, 2)}</TableCell>
                    <TableCell align="right">{formatNumber(a.metrics?.tss, 0)}</TableCell>
                    <TableCell align="right">{formatWatts(a.metrics?.mmp["20m_critical_power"])}</TableCell>
                    <TableCell>
                      <Chip size="small" label={a.source} variant="outlined" />
                      {!a.hasPower && <Chip size="small" label="파워 없음" sx={{ ml: 0.5 }} />}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {list.hasNextPage && (
            <Button sx={{ mt: 1 }} onClick={() => list.fetchNextPage()} disabled={list.isFetchingNextPage}>
              더 보기
            </Button>
          )}
        </CardContent>
      </Card>
    </Stack>
  );
}
