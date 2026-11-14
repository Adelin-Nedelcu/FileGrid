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
}

export interface FileSearch {
  category?: DataCategory | '';
  q?: string;
  region?: string;
  page?: number;
  size?: number;
}
