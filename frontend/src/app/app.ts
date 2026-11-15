import { Component, DestroyRef, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FileGridApi } from './filegrid-api';
import { UploadPanel } from './upload-panel/upload-panel';
import { FileList } from './file-list/file-list';
import { Login } from './login/login';
import { StatsPage } from './stats/stats';
import { Audit } from './audit/audit';
import { SessionStore } from './session';
import { MANUAL_CATEGORIES, StorageType, SystemStatus } from './models';

@Component({
  selector: 'app-root',
  imports: [UploadPanel, FileList, Login, StatsPage, Audit],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  private readonly api = inject(FileGridApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly session = inject(SessionStore);

  protected readonly storageTypes: StorageType[] = ['DATABASE', 'FTP', 'S3'];

  protected readonly status = signal<SystemStatus | null>(null);
  protected readonly statusError = signal(false);
  protected readonly refreshTick = signal(0);
  protected readonly view = signal<'files' | 'stats' | 'audit'>('files');

  protected readonly canAudit = computed(() => {
    const readable = this.session.user()?.readableCategories ?? [];
    const policies = this.status()?.policies ?? {};
    return readable.some((category) => policies[category]?.auditAccess);
  });

  constructor() {
    effect(() => {
      if (this.session.user()) {
        this.loadStatus();
      } else {
        this.status.set(null);
        this.view.set('files');
      }
    });
  }

  ngOnInit(): void {
    this.session.restore();
    const timer = setInterval(() => {
      if (this.session.user()) this.loadStatus();
    }, 10_000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  protected canUpload(): boolean {
    const writable = this.session.user()?.writableCategories ?? [];
    return MANUAL_CATEGORIES.some((c) => writable.includes(c));
  }

  protected onUploaded(): void {
    this.refreshTick.update((n) => n + 1);
  }

  private loadStatus(): void {
    this.api.status().subscribe({
      next: (s) => {
        this.status.set(s);
        this.statusError.set(false);
      },
      error: () => this.statusError.set(true),
    });
  }
}
