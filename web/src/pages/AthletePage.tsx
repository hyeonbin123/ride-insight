// [파일 역할] 선수·FTP 화면(/athlete). 왼쪽 카드는 현재 선수 정보와 FTP 등록, 오른쪽 카드는 새 선수 만들기와
// 기존 선수 id로 전환이다.
// [왜 필요한가] 지표(IF·TSS)와 FTP 추정 비교는 모두 선수의 FTP 이력에 기대므로, 업로드 전에 선수를 만들고
// FTP를 등록하는 곳이 필요하다. 인증이 없는 로컬 데모라 선수 '로그인' 대신 id로 선택한다(README 한계).
// [데이터 흐름] 서버 변경은 useMutation으로 보내고, 성공하면 invalidateQueries()로 캐시 전체를 무효화해
// 메뉴바·대시보드·목록이 새 선수나 다시 계산된 TSS를 받아 오게 한다.

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
import { dateIn } from "../format";
import { useAthleteStore } from "../state/athleteStore";

/**
 * 작업할 선수를 만들거나 바꾸고 FTP 이력을 관리한다.
 * 선택된 선수 id와 바꾸는 함수는 Zustand에서, 선수 정보는 React Query(['athlete', id], 메뉴바와 같은
 * 캐시)에서 가져온다.
 */
export function AthletePage() {
  const { athleteId, setAthleteId } = useAthleteStore();
  const qc = useQueryClient();
  const athlete = useQuery({ queryKey: ["athlete", athleteId], queryFn: () => api.getAthlete(athleteId!), enabled: !!athleteId });

  // 입력값 state. 숫자 칸도 문자열로 들고 있다가 보낼 때 Number로 바꾼다(빈 칸은 undefined로 보내 서버에서
  // 선택 항목이 되게). FTP 적용 시작일은 비워 두면 선수 시간대의 오늘(format.ts의 dateIn)을 쓴다. 서버가 활동
  // 날짜를 선수 시간대로 세므로 같은 기준이다(2026-10-15 전에는 UTC 날짜).
  const [form, setForm] = useState({ name: "", gender: "", birthYear: "", weightKg: "", ftp: "" });
  const [existingId, setExistingId] = useState("");
  const [threshold, setThreshold] = useState({ effectiveFrom: "", ftpWatts: "" });
  const [now] = useState(() => new Date()); // 처음 렌더 때 한 번만 잡은 지금 시각
  const effectiveFrom = threshold.effectiveFrom || dateIn(now, athlete.data?.timeZone);
  // 시간대 입력 칸. 비어 있으면 지금 시간대를 보여 준다.
  const [zoneInput, setZoneInput] = useState("");

  // 새 선수 만들기: 성공하면 그 선수를 바로 작업 선수로 고르고 캐시를 모두 무효화한다.
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

  // 기존 선수 id로 전환: 먼저 GET으로 실제로 있는 선수인지 확인한 뒤에만 선택한다(없는 id면 404 오류를 보여
  // 주고 선택은 그대로).
  const useExisting = useMutation({
    mutationFn: () => api.getAthlete(existingId.trim()),
    onSuccess: (a) => {
      setAthleteId(a.id);
      qc.invalidateQueries();
    },
  });

  // FTP 등록: PUT /athletes/{id}/threshold. 서버가 적용 시작일 이후 활동의 IF·TSS를 다시 계산하고 그 개수를
  // 돌려주므로, 성공 메시지에 표시하고 캐시를 무효화해 목록·대시보드가 새 값을 받게 한다.
  const setFtp = useMutation({
    mutationFn: () => api.setThreshold(athleteId!, effectiveFrom, Number(threshold.ftpWatts)),
    onSuccess: () => qc.invalidateQueries(),
  });

  // 시간대 변경: PUT /athletes/{id}/time-zone. 서버가 모든 활동의 지표를 다시 계산하고(현지 날짜가 바뀌면 적용
  // FTP가 바뀔 수 있다) 그 수를 돌려준다. 모르는 이름이면 400 오류를 보여 준다.
  const setZone = useMutation({
    mutationFn: () => api.setTimeZone(athleteId!, zoneInput.trim()),
    onSuccess: () => {
      setZoneInput("");
      qc.invalidateQueries();
    },
  });

  // 오류 표시 문자열: 서버 오류면 '코드: 메시지'(예: validation: ...), 그 밖의 예외면 메시지만.
  const errorText = (e: unknown) => (e instanceof ApiRequestError ? `${e.code}: ${e.message}` : e instanceof Error ? e.message : null);

  return (
    <Grid container spacing={2}>
      <Grid size={{ xs: 12, md: 6 }}>
        <Card>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              현재 선수
            </Typography>
            {/* 현재 선수 정보: 이름, id(오른쪽 '기존 선수 id로 전환' 칸에 넣는 값), 현재 FTP와 적용 */}
            {/* 시작일. '선수 선택 해제'는 저장소의 id를 지운다. */}
            {athlete.data ? (
              <Stack spacing={0.5}>
                <Typography>{athlete.data.name}</Typography>
                <Typography variant="body2" color="text.secondary">
                  id {athlete.data.id}
                </Typography>
                <Typography variant="body2">
                  FTP {athlete.data.currentThreshold ? `${athlete.data.currentThreshold.ftpWatts} W (${athlete.data.currentThreshold.effectiveFrom}부터)` : "미설정"}
                </Typography>
                <Typography variant="body2">시간대 {athlete.data.timeZone} (날짜를 이 시간대로 셉니다)</Typography>
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
            {/* FTP 입력 줄: 날짜 입력, FTP(W) 숫자 입력(브라우저 입력 힌트로 50~1000, 실제 검증은 서버), */}
            {/* 저장 버튼. 선수가 없거나 FTP가 비었거나 요청 중이면 버튼을 막아 중복 요청을 피한다. */}
            <Stack sx={{ alignItems: "flex-start" }} direction="row" spacing={1}>
              <TextField
                type="date"
                size="small"
                label="적용 시작일"
                value={effectiveFrom}
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
            {/* 저장 결과: 성공이면 다시 계산한 라이딩 수, 실패면 서버 오류 코드와 메시지. */}
            {setFtp.isSuccess && <Alert severity="success" sx={{ mt: 1 }}>저장했습니다. 다시 계산한 라이딩 {setFtp.data.recomputedActivities}개</Alert>}
            {setFtp.isError && <Alert severity="error" sx={{ mt: 1 }}>{errorText(setFtp.error)}</Alert>}
            <Divider sx={{ my: 2 }} />
            <Typography variant="subtitle1" gutterBottom>
              시간대 변경
            </Typography>
            {/* 시간대 입력(IANA 이름, 예: Asia/Seoul, Europe/Berlin, UTC)과 변경 버튼. */}
            <Stack sx={{ alignItems: "flex-start" }} direction="row" spacing={1}>
              <TextField
                size="small"
                label="시간대"
                placeholder={athlete.data?.timeZone ?? "Asia/Seoul"}
                value={zoneInput}
                onChange={(e) => setZoneInput(e.target.value)}
              />
              <Button variant="outlined" disabled={!athleteId || !zoneInput.trim() || setZone.isPending} onClick={() => setZone.mutate()}>
                변경
              </Button>
            </Stack>
            {setZone.isSuccess && (
              <Alert severity="success" sx={{ mt: 1 }}>
                {setZone.data.timeZone}로 바꿨습니다. 다시 계산한 라이딩 {setZone.data.recomputedActivities}개
              </Alert>
            )}
            {setZone.isError && <Alert severity="error" sx={{ mt: 1 }}>{errorText(setZone.error)}</Alert>}
          </CardContent>
        </Card>
      </Grid>
      <Grid size={{ xs: 12, md: 6 }}>
        <Card>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              새 선수 만들기
            </Typography>
            {/* 새 선수 입력 칸: 이름(필수), 성별(M/F/빈칸), 출생 연도, 체중, 현재 FTP(선택). 이름이 */}
            {/* 비었으면 만들기 버튼을 막는다. */}
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
            {/* 기존 선수 id 입력과 전환 버튼(앞뒤 공백은 잘라서 조회). */}
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
