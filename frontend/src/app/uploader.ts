import { HttpClient, HttpErrorResponse, HttpEvent, HttpEventType, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subscription, TimeoutError, timeout } from 'rxjs';
import { DataCategory, FileRecord, UploadSession } from './models';

export interface UploadMeta {
  category: DataCategory;
  region?: string;
  description?: string;
}

export interface UploadProgress {
  loaded: number;
  total: number;
  note: string | null;
}

export class UploadCancelled extends Error {
  constructor() {
    super('Upload anulat.');
  }
}

const STALL_MS = 90_000;
const MAX_BACKOFF_MS = 30_000;
const RETRYABLE = new Set([0, 408, 409, 425, 429, 500, 502, 503, 504]);

export class UploadTask {
  readonly result: Promise<FileRecord>;
  private cancelled = false;
  private subscription: Subscription | null = null;
  private wake: (() => void) | null = null;
  private rejectCurrent: ((reason: unknown) => void) | null = null;
  private sessionId: string | null = null;

  constructor(
    private readonly http: HttpClient,
    private readonly file: File,
    private readonly meta: UploadMeta,
    private readonly chunkSize: number,
    private readonly report: (progress: UploadProgress) => void,
  ) {
    this.result = this.run();
  }

  cancel(): void {
    this.cancelled = true;
    this.subscription?.unsubscribe();
    this.rejectCurrent?.(new UploadCancelled());
    this.wake?.();
    if (this.sessionId) {
      this.http.delete(`/api/uploads/${this.sessionId}`).subscribe({ error: () => undefined });
    }
  }

  private async run(): Promise<FileRecord> {
    const clientKey = await fingerprint(this.file, this.meta.category);
    return this.file.size <= this.chunkSize ? this.direct(clientKey) : this.chunked(clientKey);
  }

  private direct(clientKey: string): Promise<FileRecord> {
    let params = new HttpParams().set('category', this.meta.category).set('name', this.file.name);
    if (this.meta.region) params = params.set('region', this.meta.region);
    if (this.meta.description?.trim()) params = params.set('description', this.meta.description.trim());
    return this.retrying(0, () =>
      this.send<FileRecord>(
        this.http.post<FileRecord>('/api/files', this.file, {
          params,
          headers: { 'Content-Type': this.file.type || 'application/octet-stream', 'Idempotency-Key': clientKey },
          reportProgress: true,
          observe: 'events',
        }),
        (loaded) => this.progress(loaded),
      ),
    );
  }

  private async chunked(clientKey: string): Promise<FileRecord> {
    const session = await this.retrying(0, () =>
      this.send<UploadSession>(
        this.http.post<UploadSession>(
          '/api/uploads',
          {
            category: this.meta.category,
            name: this.file.name,
            size: this.file.size,
            contentType: this.file.type || 'application/octet-stream',
            region: this.meta.region || null,
            description: this.meta.description?.trim() || null,
            clientKey,
          },
          { observe: 'events' },
        ),
      ),
    );
    if (session.completed && session.file) {
      return session.file;
    }
    this.sessionId = session.id;

    let pending = this.missing(session, session.receivedParts);
    for (let round = 0; pending.length > 0; round++) {
      if (round > 5) {
        throw new Error('Fișierul nu a putut fi asamblat după mai multe încercări.');
      }
      let confirmed = this.file.size - pending.reduce((sum, n) => sum + this.partBlob(session, n).size, 0);
      for (const partNumber of pending) {
        const blob = this.partBlob(session, partNumber);
        const hash = await sha256Hex(blob);
        const base = confirmed;
        await this.retrying(base, () =>
          this.send(
            this.http.put(`/api/uploads/${session.id}/parts/${partNumber}`, blob, {
              headers: { 'Content-Type': 'application/octet-stream', 'X-Part-SHA256': hash },
              reportProgress: true,
              observe: 'events',
            }),
            (loaded) => this.progress(base + loaded),
          ),
          [422],
        );
        confirmed += blob.size;
        this.progress(confirmed);
      }
      try {
        this.report({ loaded: this.file.size, total: this.file.size, note: 'Se verifică fișierul pe server…' });
        const stored = await this.retrying(this.file.size, () =>
          this.send<FileRecord>(this.http.post<FileRecord>(`/api/uploads/${session.id}/complete`, null, { observe: 'events' }), undefined, 0),
        );
        this.sessionId = null;
        return stored;
      } catch (err) {
        const missing = err instanceof HttpErrorResponse ? (err.error?.missingParts as number[] | undefined) : undefined;
        if (!missing?.length) throw err;
        pending = missing;
      }
    }
    throw new Error('Upload incomplet.');
  }

  private missing(session: UploadSession, received: number[]): number[] {
    const done = new Set(received);
    const parts: number[] = [];
    for (let n = 1; n <= session.partCount; n++) {
      if (!done.has(n)) parts.push(n);
    }
    return parts;
  }

  private partBlob(session: UploadSession, partNumber: number): Blob {
    const start = (partNumber - 1) * session.partSize;
    return this.file.slice(start, Math.min(start + session.partSize, this.file.size));
  }

  private progress(loaded: number): void {
    this.report({ loaded: Math.min(loaded, this.file.size), total: this.file.size, note: null });
  }

  private send<T>(request: Observable<HttpEvent<T>>, onLoaded?: (loaded: number) => void, stallMs = STALL_MS): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      this.rejectCurrent = reject;
      const source = stallMs > 0 ? request.pipe(timeout({ each: stallMs })) : request;
      this.subscription = source.subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress) onLoaded?.(event.loaded);
          else if (event.type === HttpEventType.Response) resolve(event.body as T);
        },
        error: reject,
      });
    });
  }

  private async retrying<T>(loadedBefore: number, operation: () => Promise<T>, alsoRetry: number[] = []): Promise<T> {
    for (let attempt = 1; ; attempt++) {
      if (this.cancelled) throw new UploadCancelled();
      try {
        return await operation();
      } catch (err) {
        if (this.cancelled) throw new UploadCancelled();
        if (!isRetryable(err, alsoRetry) || (isConflictWithMissingParts(err))) throw err;
        const delay = Math.max(retryAfterMs(err), Math.min(MAX_BACKOFF_MS, 1000 * 2 ** Math.min(attempt - 1, 5)));
        this.report({
          loaded: loadedBefore,
          total: this.file.size,
          note: `Conexiune instabilă — reîncerc automat în ${Math.round(delay / 1000)} s (încercarea ${attempt + 1})`,
        });
        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, delay);
          const online = () => resolve();
          window.addEventListener('online', online, { once: true });
          this.wake = () => {
            clearTimeout(timer);
            resolve();
          };
        });
        this.wake = null;
      }
    }
  }
}

@Injectable({ providedIn: 'root' })
export class Uploader {
  private readonly http = inject(HttpClient);

  start(file: File, meta: UploadMeta, chunkSize: number, report: (progress: UploadProgress) => void): UploadTask {
    return new UploadTask(this.http, file, meta, chunkSize, report);
  }
}

function isRetryable(err: unknown, alsoRetry: number[]): boolean {
  if (err instanceof TimeoutError) return true;
  return err instanceof HttpErrorResponse && (RETRYABLE.has(err.status) || alsoRetry.includes(err.status));
}

function isConflictWithMissingParts(err: unknown): boolean {
  return err instanceof HttpErrorResponse && err.status === 409 && Array.isArray(err.error?.missingParts);
}

function retryAfterMs(err: unknown): number {
  if (!(err instanceof HttpErrorResponse)) return 0;
  const seconds = Number(err.headers?.get('Retry-After'));
  return Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : 0;
}

async function sha256Hex(data: Blob | string): Promise<string> {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : await data.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

function fingerprint(file: File, category: DataCategory): Promise<string> {
  return sha256Hex(`${category}|${file.name}|${file.size}|${file.lastModified}`);
}
