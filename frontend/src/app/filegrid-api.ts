import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AuditEntry, FileRecord, FileSearch, Page, Stats, SystemStatus, VerificationResult } from './models';

@Injectable({ providedIn: 'root' })
export class FileGridApi {
  private readonly http = inject(HttpClient);

  status(): Observable<SystemStatus> {
    return this.http.get<SystemStatus>('/api/system/status');
  }

  search(filter: FileSearch): Observable<Page<FileRecord>> {
    let params = new HttpParams().set('page', filter.page ?? 0).set('size', filter.size ?? 20);
    for (const category of filter.categories ?? []) params = params.append('category', category);
    if (filter.q?.trim()) params = params.set('q', filter.q.trim());
    if (filter.region) params = params.set('region', filter.region);
    return this.http.get<Page<FileRecord>>('/api/files', { params });
  }

  verify(id: string): Observable<VerificationResult> {
    return this.http.post<VerificationResult>(`/api/files/${id}/verify`, null);
  }

  audit(filter: { action?: string; q?: string; page: number; size: number }): Observable<Page<AuditEntry>> {
    let params = new HttpParams().set('page', filter.page).set('size', filter.size);
    if (filter.action) params = params.set('action', filter.action);
    if (filter.q?.trim()) params = params.set('q', filter.q.trim());
    return this.http.get<Page<AuditEntry>>('/api/audit', { params });
  }

  stats(hours: number): Observable<Stats> {
    return this.http.get<Stats>('/api/stats', { params: { hours } });
  }

  runBenchmark(hours: number): Observable<Stats> {
    return this.http.post<Stats>('/api/stats/benchmark', null, { params: { hours } });
  }

  contentUrl(id: string): string {
    return `/api/files/${id}/content`;
  }
}

export function errorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) return 'Serverul nu poate fi contactat.';
    const body = err.error as { title?: string; detail?: string } | null;
    if (body?.detail) return `${body.title ?? 'Eroare'}: ${body.detail}`;
    return `Eroare ${err.status} ${err.statusText}`;
  }
  if (err instanceof Error) return err.message;
  return String(err);
}
