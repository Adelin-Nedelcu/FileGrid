export type DataCategory = 'METER_READING' | 'INCIDENT_RECORDING' | 'CONNECTION_DOCUMENT' | 'DRONE_VIDEO';
export type StorageType = 'DATABASE' | 'FTP' | 'S3';

export const CATEGORY_LABELS: Record<DataCategory, string> = {
  METER_READING: 'Citiri contoare',
  INCIDENT_RECORDING: 'Înregistrări de avarie',
  CONNECTION_DOCUMENT: 'Documente de racordare',
  DRONE_VIDEO: 'Video-uri inspecții dronă',
};

export const MANUAL_CATEGORIES: DataCategory[] = ['INCIDENT_RECORDING', 'CONNECTION_DOCUMENT', 'DRONE_VIDEO'];
export const ALL_CATEGORIES: DataCategory[] = ['METER_READING', ...MANUAL_CATEGORIES];

export const REGIONS: { group: string; counties: { code: string; name: string }[] }[] = [
  {
    group: 'Muntenia',
    counties: [
      { code: 'B', name: 'București' },
      { code: 'IF', name: 'Ilfov' },
      { code: 'GR', name: 'Giurgiu' },
    ],
  },
  {
    group: 'Dobrogea',
    counties: [
      { code: 'CT', name: 'Constanța' },
      { code: 'TL', name: 'Tulcea' },
      { code: 'CL', name: 'Călărași' },
      { code: 'IL', name: 'Ialomița' },
    ],
  },
  {
    group: 'Banat',
    counties: [
      { code: 'TM', name: 'Timiș' },
      { code: 'AR', name: 'Arad' },
      { code: 'CS', name: 'Caraș-Severin' },
      { code: 'HD', name: 'Hunedoara' },
    ],
  },
];

const REGION_NAMES = new Map(REGIONS.flatMap((g) => g.counties.map((c) => [c.code, c.name] as const)));

export function regionName(code: string | null): string {
  return code ? (REGION_NAMES.get(code) ?? code) : '—';
}

export interface FileRecord {
  id: string;
  category: DataCategory;
  storage: StorageType;
  name: string;
  contentType: string | null;
  sizeBytes: number | null;
  sha256: string | null;
  encrypted: boolean;
  sourceId: string | null;
  region: string | null;
  description: string | null;
  createdAt: string;
  storedAt: string | null;
  lastVerifiedAt: string | null;
  lastVerificationOk: boolean | null;
  duplicate: boolean;
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface VerificationResult {
  fileId: string;
  match: boolean;
  expectedSha256: string;
  actualSha256: string | null;
  expectedSize: number;
  actualSize: number | null;
  storage: StorageType;
  durationMs: number;
  verifiedAt: string;
  detail: string;
}

export interface Policy {
  storage: StorageType;
  maxFileSizeBytes: number;
  maxConcurrentUploads: number;
  encryptAtRest: boolean;
  requireIdempotencyKey: boolean;
  auditAccess: boolean;
}

export interface SystemStatus {
  storages: Partial<Record<StorageType, { up: boolean; checkedAt: string | null }>>;
  policies: Partial<Record<DataCategory, Policy>>;
  uploadChunkSizeBytes: number;
}

export interface Session {
  username: string;
  displayName: string;
  roles: string[];
  readableCategories: DataCategory[];
  writableCategories: DataCategory[];
  canViewStats: boolean;
  canRunBenchmark: boolean;
}

export interface AuditEntry {
  id: number;
  fileId: string;
  fileName: string;
  category: DataCategory;
  action: string;
  actor: string;
  clientIp: string | null;
  requestId: string | null;
  success: boolean;
  detail: string | null;
  occurredAt: string;
}

export interface StorageMeasurement {
  storage: StorageType;
  files: number;
  fileSizeBytes: number;
  writeP50Ms: number | null;
  writeP95Ms: number | null;
  readP50Ms: number | null;
  readP95Ms: number | null;
  writeMbPerS: number | null;
  readMbPerS: number | null;
  errors: number;
  note: string | null;
  eligible: boolean;
}

export interface CategoryFit {
  category: DataCategory;
  currentStorage: StorageType;
  fastestStorage: StorageType | null;
  recommendedStorage: StorageType | null;
  criterion: string;
  verdict: string;
  measurements: StorageMeasurement[];
}

export interface LiveUsage {
  category: DataCategory;
  storage: StorageType;
  files: number;
  bytes: number;
  p50Ms: number | null;
  p95Ms: number | null;
  failed: number;
}

export interface LoadPhase {
  minutes: number;
  stored: number;
  failed: number;
  bytes: number;
  filesPerMinute: number;
  p50Ms: number | null;
  p95Ms: number | null;
}

export interface CategoryLoad {
  category: DataCategory;
  normal: LoadPhase | null;
  peak: LoadPhase | null;
}

export interface RegionStat {
  region: string;
  name: string;
  readings: number;
  meters: number;
  avgKw: number | null;
  outages: number;
  sags: number;
  spikes: number;
  anomaliesPer1000: number;
  incidents: number;
  droneVideos: number;
}

export interface ConsumerStat {
  type: string;
  readings: number;
  meters: number;
  avgKw: number | null;
  maxKw: number | null;
}

export interface MeterIssue {
  meterId: string;
  region: string | null;
  consumerType: string | null;
  readings: number;
  outages: number;
  sags: number;
  spikes: number;
  affected: number;
}

export interface TimelineCell {
  stored: number;
  failed: number;
  p95Ms: number | null;
  peak: boolean;
}

export interface TimelineBucket {
  start: string;
  categories: Record<DataCategory, TimelineCell>;
  stored: number;
  failed: number;
  peak: boolean;
}

export interface Stats {
  hours: number;
  generatedAt: string;
  storage: { measuredAt: string | null; runs: number; fit: CategoryFit[]; live: LiveUsage[]; summary: string[] };
  load: { categories: CategoryLoad[]; conclusions: string[] };
  timeline: { unit: '5min' | '30min' | 'hour' | 'day'; buckets: TimelineBucket[]; conclusions: string[] };
  network: {
    readingsAnalyzed: number;
    regions: RegionStat[];
    consumers: ConsumerStat[];
    problemMeters: MeterIssue[];
    silentMeters: number;
    silentSample: { meterId: string; region: string | null; lastSeen: string }[];
    conclusions: string[];
  };
}

export interface UploadSession {
  id: string;
  completed: boolean;
  partSize: number;
  partCount: number;
  receivedParts: number[];
  file: FileRecord | null;
}

export interface FileSearch {
  categories?: DataCategory[];
  q?: string;
  region?: string;
  page?: number;
  size?: number;
}
