import type {
  Activity,
  ActivityList,
  ApiError,
  Athlete,
  CreateAthleteRequest,
  PowerCurve,
  Samples,
  TrainingLoadResponse,
} from "./types";

export class ApiRequestError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, body: ApiError | null) {
    super(body?.message ?? `request failed with status ${status}`);
    this.status = status;
    this.code = body?.error ?? "http_error";
  }
}

async function parseError(res: Response): Promise<ApiRequestError> {
  let body: ApiError | null = null;
  try {
    body = (await res.json()) as ApiError;
  } catch {
    body = null;
  }
  return new ApiRequestError(res.status, body);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, init);
  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

function json(method: string, body: unknown): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

export const api = {
  createAthlete: (req: CreateAthleteRequest) => request<Athlete>("/api/athletes", json("POST", req)),
  getAthlete: (id: string) => request<Athlete>(`/api/athletes/${id}`),
  setThreshold: (id: string, effectiveFrom: string, ftpWatts: number) =>
    request<{ recomputedActivities: number }>(
      `/api/athletes/${id}/threshold`,
      json("PUT", { effectiveFrom, ftpWatts }),
    ),
  uploadActivity: (athleteId: string, file: File) => {
    const form = new FormData();
    form.append("file", file, file.name);
    return request<Activity>(`/api/athletes/${athleteId}/activities`, { method: "POST", body: form });
  },
  listActivities: (athleteId: string, limit = 50, before?: string) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (before) params.set("before", before);
    return request<ActivityList>(`/api/athletes/${athleteId}/activities?${params}`);
  },
  getActivity: (id: string) => request<Activity>(`/api/activities/${id}`),
  getSamples: (id: string, every = 1) => request<Samples>(`/api/activities/${id}/samples?every=${every}`),
  getActivityPowerCurve: (id: string) => request<PowerCurve>(`/api/activities/${id}/power-curve`),
  getAthletePowerCurve: (athleteId: string, weeks = 6, asOf?: string) => {
    const params = new URLSearchParams({ weeks: String(weeks) });
    if (asOf) params.set("asOf", asOf);
    return request<PowerCurve>(`/api/athletes/${athleteId}/power-curve?${params}`);
  },
  getTrainingLoad: (athleteId: string, from: string, to: string) =>
    request<TrainingLoadResponse>(`/api/athletes/${athleteId}/training-load?from=${from}&to=${to}`),
  deleteActivity: (id: string) => request<void>(`/api/activities/${id}`, { method: "DELETE" }),
};
