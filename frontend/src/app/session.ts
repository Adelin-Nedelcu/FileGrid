import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { Session } from './models';

@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly http = inject(HttpClient);

  readonly user = signal<Session | null>(null);
  readonly loaded = signal(false);

  restore(): void {
    this.http.get<Session>('/api/auth/session').subscribe({
      next: (session) => {
        this.user.set(session);
        this.loaded.set(true);
      },
      error: () => {
        this.user.set(null);
        this.loaded.set(true);
      },
    });
  }

  login(username: string, password: string, onError: (err: unknown) => void): void {
    this.http.post<Session>('/api/auth/login', { username, password }).subscribe({
      next: (session) => this.user.set(session),
      error: onError,
    });
  }

  logout(): void {
    this.http.post('/api/auth/logout', null).subscribe({
      next: () => this.user.set(null),
      error: () => this.user.set(null),
    });
  }

  expire(): void {
    this.user.set(null);
  }
}

export const sessionInterceptor: HttpInterceptorFn = (request, next) => {
  const store = inject(SessionStore);
  return next(request).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && !request.url.startsWith('/api/auth/')) {
        store.expire();
      }
      return throwError(() => err);
    }),
  );
};
