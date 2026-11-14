import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { FileGridApi, errorMessage } from './filegrid-api';
import { UploadPanel } from './upload-panel/upload-panel';
import { FileList } from './file-list/file-list';
import { StorageType, SystemStatus } from './models';

@Component({
  selector: 'app-root',
  imports: [UploadPanel, FileList],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  private readonly api = inject(FileGridApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly storageTypes: StorageType[] = ['DATABASE', 'FTP', 'S3'];

  protected readonly status = signal<SystemStatus | null>(null);
  protected readonly statusError = signal<string | null>(null);
  protected readonly refreshTick = signal(0);

  ngOnInit(): void {
    this.loadStatus();
    const timer = setInterval(() => this.loadStatus(), 10_000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  protected onUploaded(): void {
    this.refreshTick.update((n) => n + 1);
  }

  private loadStatus(): void {
    this.api.status().subscribe({
      next: (s) => {
        this.status.set(s);
        this.statusError.set(null);
      },
      error: (err) => this.statusError.set(errorMessage(err)),
    });
  }
}
