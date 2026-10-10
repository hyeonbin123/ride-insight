// Mirrors server/src/main/kotlin/com/rideinsight/api/Dtos.kt

export interface Threshold {
  effectiveFrom: string; // ISO date
  ftpWatts: number;
  source: string;
}

export interface Athlete {
  id: string;
  name: string;
  gender?: string;
  birthYear?: number;
  weightKg?: number;
  createdAt: string;
  currentThreshold?: Threshold;
}

export interface CreateAthleteRequest {
  name: string;
  gender?: string;
  birthYear?: number;
  weightKg?: number;
  ftp?: number;
}

export interface Metrics {
  ftpUsed?: number;
  normalizedPower?: number;
  intensityFactor?: number;
  tss?: number;
  averagePower?: number;
  maxPower?: number;
  workKj?: number;
  mmp: Record<string, number | null>;
}

export interface Activity {
  id: string;
  athleteId: string;
  startedAt: string;
  source: string;
  sourceName?: string;
  sport?: string;
  recIntSecs?: number;
  intervalShare?: number;
  sampleCount: number;
  durationSecs?: number;
  device: { manufacturer?: number; product?: number };
  crcValid?: boolean;
  hasPower: boolean;
  powerMissing: number;
  metrics?: Metrics;
}

export interface ActivityList {
  items: Activity[];
  nextBefore?: string;
}

export interface Samples {
  every: number;
  secs: number[];
  power?: (number | null)[];
  heartRate?: (number | null)[];
  cadence?: (number | null)[];
  distanceM?: (number | null)[];
  altitudeM?: (number | null)[];
}

export interface CurvePoint {
  secs: number;
  watts: number;
}

export interface PowerCurve {
  points: CurvePoint[];
  standard: Record<string, number | null>;
}

export interface TrainingLoadDay {
  date: string;
  tss: number;
  ctl: number;
  atl: number;
  tsb: number;
}

export interface TrainingLoadResponse {
  days: TrainingLoadDay[];
}

export interface ApiError {
  error: string;
  message: string;
}

export interface FtpEstimate {
  method: string;
  ftpWatts?: number;
  rules: Record<string, number | null>;
  criticalPowerWPrimeKj?: number;
  windowsUsed: number;
  ridesInWindow: number;
  best20m?: number;
  best60m?: number;
  weeks: number;
  note: string;
}
