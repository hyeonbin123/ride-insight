import { useRef, useState } from "react";
import {
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
  LinearProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Link as RouterLink } from "react-router";
import { api, ApiRequestError } from "../api/client";
import type { Activity } from "../api/types";
import { formatDate, formatDuration, formatNumber, formatWatts } from "../format";
import { useAthleteStore } from "../state/athleteStore";
import { NeedAthlete } from "../components/NeedAthlete";

const PAGE = 30;

/** FIT upload plus the paginated activity list. */
export function ActivitiesPage() {
  const athleteId = useAthleteStore((s) => s.athleteId);
  const qc = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);
  const [uploadLog, setUploadLog] = useState<{ name: string; ok: boolean; text: string }[]>([]);

  const list = useInfiniteQuery({
    queryKey: ["activities", athleteId],
    queryFn: ({ pageParam }) => api.listActivities(athleteId!, PAGE, pageParam ?? undefined),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextBefore ?? undefined,
    enabled: !!athleteId,
  });

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
          results.push({ name: f.name, ok: false, text: e instanceof ApiRequestError ? `${e.status} ${e.code}: ${e.message}` : String(e) });
        }
      }
      return results;
    },
    onSuccess: (results) => {
      setUploadLog((prev) => [...results, ...prev].slice(0, 20));
      qc.invalidateQueries({ queryKey: ["activities", athleteId] });
      qc.invalidateQueries({ queryKey: ["training-load", athleteId] });
      qc.invalidateQueries({ queryKey: ["athlete-curve", athleteId] });
    },
  });

  if (!athleteId) return <NeedAthlete />;
  const items: Activity[] = list.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <Stack sx={{ alignItems: "center", flexWrap: "wrap" }} direction="row" spacing={2}>
            <Typography variant="h6">FIT 파일 올리기</Typography>
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
          <Typography variant="h6" gutterBottom>
            라이딩 {items.length ? `(${items.length}${list.hasNextPage ? "+" : ""})` : ""}
          </Typography>
          {list.isLoading && <LinearProgress />}
          {list.isError && <Alert severity="error">{String(list.error)}</Alert>}
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
                {items.map((a) => (
                  <TableRow key={a.id} hover component={RouterLink} to={`/activities/${a.id}`} sx={{ textDecoration: "none", cursor: "pointer" }}>
                    <TableCell>{formatDate(a.startedAt)}</TableCell>
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
