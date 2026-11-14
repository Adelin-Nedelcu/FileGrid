import { Component, computed, inject, input, output, signal } from '@angular/core';
import { HttpEventType } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { FileGridApi, errorMessage } from '../filegrid-api';
import { BytesPipe } from '../bytes.pipe';
import { CATEGORY_LABELS, DataCategory, FileRecord, MANUAL_CATEGORIES, Policy, REGIONS } from '../models';

@Component({
  selector: 'app-upload-panel',
  imports: [FormsModule, BytesPipe],
  templateUrl: './upload-panel.html',
  styleUrl: './upload-panel.css',
})
export class UploadPanel {
  private readonly api = inject(FileGridApi);

  readonly policies = input<Partial<Record<DataCategory, Policy>>>({});
  readonly uploaded = output<FileRecord>();

  protected readonly categories = MANUAL_CATEGORIES;
  protected readonly labels = CATEGORY_LABELS;
  protected readonly regions = REGIONS;

  protected readonly category = signal<DataCategory>('INCIDENT_RECORDING');
  protected readonly region = signal('');
  protected readonly description = signal('');
  protected readonly file = signal<File | null>(null);

  protected readonly progress = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly result = signal<FileRecord | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly policy = computed(() => this.policies()[this.category()] ?? null);
  protected readonly tooLarge = computed(() => {
    const f = this.file();
    const p = this.policy();
    return !!f && !!p && f.size > p.maxFileSizeBytes;
  });

  private idempotencyKey: string | null = null;
  private subscription: Subscription | null = null;

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
    this.idempotencyKey = null;
    this.result.set(null);
    this.error.set(null);
    this.progress.set(null);
  }

  protected upload(): void {
    const file = this.file();
    if (!file || this.busy() || this.tooLarge()) return;

    this.idempotencyKey ??= crypto.randomUUID();
    this.busy.set(true);
    this.error.set(null);
    this.result.set(null);
    this.progress.set(0);

    this.subscription = this.api
      .upload(file, {
        category: this.category(),
        region: this.region(),
        description: this.description(),
        idempotencyKey: this.idempotencyKey,
      })
      .subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            this.progress.set(Math.round((100 * event.loaded) / event.total));
          } else if (event.type === HttpEventType.Response && event.body) {
            this.progress.set(100);
            this.result.set(event.body);
            this.uploaded.emit(event.body);
            this.idempotencyKey = null;
          }
        },
        error: (err) => {
          this.error.set(errorMessage(err));
          this.busy.set(false);
        },
        complete: () => this.busy.set(false),
      });
  }

  protected cancel(): void {
    this.subscription?.unsubscribe();
    this.subscription = null;
    this.busy.set(false);
    this.error.set('Upload anulat. Poți relua cu „Reîncearcă”.');
  }
}
