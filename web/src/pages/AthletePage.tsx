import { useState } from "react";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Grid,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiRequestError } from "../api/client";
import { isoDate } from "../format";
import { useAthleteStore } from "../state/athleteStore";

/** Create or switch the working athlete and manage the FTP history. */
export function AthletePage() {
  const { athleteId, setAthleteId } = useAthleteStore();
  const qc = useQueryClient();
  const athlete = useQuery({ queryKey: ["athlete", athleteId], queryFn: () => api.getAthlete(athleteId!), enabled: !!athleteId });

  const [form, setForm] = useState({ name: "", gender: "", birthYear: "", weightKg: "", ftp: "" });
  const [existingId, setExistingId] = useState("");
  const [threshold, setThreshold] = useState(() => ({ effectiveFrom: isoDate(new Date()), ftpWatts: "" }));

  const create = useMutation({
    mutationFn: () =>
      api.createAthlete({
        name: form.name,
        gender: form.gender || undefined,
        birthYear: form.birthYear ? Number(form.birthYear) : undefined,
        weightKg: form.weightKg ? Number(form.weightKg) : undefined,
        ftp: form.ftp ? Number(form.ftp) : undefined,
      }),
    onSuccess: (a) => {
      setAthleteId(a.id);
      qc.invalidateQueries();
    },
  });

  const useExisting = useMutation({
    mutationFn: () => api.getAthlete(existingId.trim()),
    onSuccess: (a) => {
      setAthleteId(a.id);
      qc.invalidateQueries();
    },
  });

  const setFtp = useMutation({
    mutationFn: () => api.setThreshold(athleteId!, threshold.effectiveFrom, Number(threshold.ftpWatts)),
    onSuccess: () => qc.invalidateQueries(),
  });

  const errorText = (e: unknown) => (e instanceof ApiRequestError ? `${e.code}: ${e.message}` : e instanceof Error ? e.message : null);

  return (
    <Grid container spacing={2}>
      <Grid size={{ xs: 12, md: 6 }}>
        <Card>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              현재 선수
            </Typography>
            {athlete.data ? (
              <Stack spacing={0.5}>
                <Typography>{athlete.data.name}</Typography>
                <Typography variant="body2" color="text.secondary">
                  id {athlete.data.id}
                </Typography>
                <Typography variant="body2">
                  FTP {athlete.data.currentThreshold ? `${athlete.data.currentThreshold.ftpWatts} W (${athlete.data.currentThreshold.effectiveFrom}부터)` : "미설정"}
                </Typography>
                <Button size="small" onClick={() => setAthleteId(null)} sx={{ alignSelf: "flex-start" }}>
                  선수 선택 해제
                </Button>
              </Stack>
            ) : (
              <Typography color="text.secondary">선수를 만들거나 기존 id를 입력하세요.</Typography>
            )}
            <Divider sx={{ my: 2 }} />
            <Typography variant="subtitle1" gutterBottom>
              FTP 등록
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              해당 날짜부터의 라이딩 TSS·IF를 이 FTP로 다시 계산합니다.
            </Typography>
            <Stack sx={{ alignItems: "flex-start" }} direction="row" spacing={1}>
              <TextField
                type="date"
                size="small"
                label="적용 시작일"
                value={threshold.effectiveFrom}
                onChange={(e) => setThreshold({ ...threshold, effectiveFrom: e.target.value })}
                slotProps={{ inputLabel: { shrink: true } }}
              />
              <TextField
                type="number"
                size="small"
                label="FTP (W)"
                value={threshold.ftpWatts}
                onChange={(e) => setThreshold({ ...threshold, ftpWatts: e.target.value })}
                slotProps={{ htmlInput: { min: 50, max: 1000 } }}
              />
              <Button variant="contained" disabled={!athleteId || !threshold.ftpWatts || setFtp.isPending} onClick={() => setFtp.mutate()}>
                저장
              </Button>
            </Stack>
            {setFtp.isSuccess && <Alert severity="success" sx={{ mt: 1 }}>저장했습니다. 다시 계산한 라이딩 {setFtp.data.recomputedActivities}개</Alert>}
            {setFtp.isError && <Alert severity="error" sx={{ mt: 1 }}>{errorText(setFtp.error)}</Alert>}
          </CardContent>
        </Card>
      </Grid>
      <Grid size={{ xs: 12, md: 6 }}>
        <Card>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              새 선수 만들기
            </Typography>
            <Stack spacing={1.5}>
              <TextField size="small" label="이름" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
              <Stack direction="row" spacing={1}>
                <TextField select size="small" label="성별" value={form.gender} onChange={(e) => setForm({ ...form, gender: e.target.value })} sx={{ minWidth: 100 }}>
                  <MenuItem value="">-</MenuItem>
                  <MenuItem value="M">M</MenuItem>
                  <MenuItem value="F">F</MenuItem>
                </TextField>
                <TextField type="number" size="small" label="출생 연도" value={form.birthYear} onChange={(e) => setForm({ ...form, birthYear: e.target.value })} />
                <TextField type="number" size="small" label="체중 (kg)" value={form.weightKg} onChange={(e) => setForm({ ...form, weightKg: e.target.value })} />
              </Stack>
              <TextField type="number" size="small" label="현재 FTP (W, 선택)" value={form.ftp} onChange={(e) => setForm({ ...form, ftp: e.target.value })} />
              <Button variant="contained" disabled={!form.name.trim() || create.isPending} onClick={() => create.mutate()}>
                만들기
              </Button>
              {create.isError && <Alert severity="error">{errorText(create.error)}</Alert>}
            </Stack>
            <Divider sx={{ my: 2 }} />
            <Typography variant="subtitle1" gutterBottom>
              기존 선수 id로 전환
            </Typography>
            <Box sx={{ display: "flex", gap: 1 }}>
              <TextField size="small" fullWidth label="athlete id" value={existingId} onChange={(e) => setExistingId(e.target.value)} />
              <Button variant="outlined" disabled={!existingId.trim() || useExisting.isPending} onClick={() => useExisting.mutate()}>
                전환
              </Button>
            </Box>
            {useExisting.isError && <Alert severity="error" sx={{ mt: 1 }}>{errorText(useExisting.error)}</Alert>}
          </CardContent>
        </Card>
      </Grid>
    </Grid>
  );
}
