import { HttpClient, HttpErrorResponse, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DataCategory, FileRecord, FileSearch, Page, SystemStatus, VerificationResult } from './models';

@Injectable({ providedIn: 'root' })
export class FileGridApi {
  private readonly http = inject(HttpClient);

  status(): Observable<SystemStatus> {
    return this.http.get<SystemStatus>('/api/system/status');
  }

  search(filter: FileSearch): Observable<Page<FileRecord>> {
    let params = new HttpParams().set('page', filter.page ?? 0).set('size', filter.size ?? 20);
    if (filter.category) params = params.set('category', filter.category);
    if (filter.q?.trim()) params = params.set('q', filter.q.trim());
    if (filter.region) params = params.set('region', filter.region);
    return this.http.get<Page<FileRecord>>('/api/files', { params });
  }

  upload(
    file: File,
    meta: { category: DataCategory; region?: string; description?: string; idempotencyKey: string },
  ): Observable<HttpEvent<FileRecord>> {
    let params = new HttpParams().set('category', meta.category).set('name', file.name);
    if (meta.region) params = params.set('region', meta.region);
    if (meta.description?.trim()) params = params.set('description', meta.description.trim());
    return this.http.post<FileRecord>('/api/files', file, {
      params,
      headers: {
        'Content-Type': file.type || 'application/octet-stream',
        'Idempotency-Key': meta.idempotencyKey,
      },
      reportProgress: true,
      observe: 'events',
    });
  }

  verify(id: string): Observable<VerificationResult> {
    return this.http.post<VerificationResult>(`/api/files/${id}/verify`, null);
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
  return String(err);
}
