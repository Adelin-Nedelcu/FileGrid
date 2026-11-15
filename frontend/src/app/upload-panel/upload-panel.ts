import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../filegrid-api';
import { BytesPipe } from '../bytes.pipe';
import { CATEGORY_LABELS, DataCategory, FileRecord, MANUAL_CATEGORIES, Policy, REGIONS } from '../models';
import { UploadCancelled, UploadTask, Uploader } from '../uploader';

@Component({
  selector: 'app-upload-panel',
  imports: [FormsModule, BytesPipe],
  templateUrl: './upload-panel.html',
  styleUrl: './upload-panel.css',
})
export class UploadPanel {
  private readonly uploader = inject(Uploader);

  readonly policies = input<Partial<Record<DataCategory, Policy>>>({});
  readonly writable = input<DataCategory[]>([]);
  readonly chunkSize = input(5 * 1024 * 1024);
  readonly uploaded = output<FileRecord>();

  protected readonly labels = CATEGORY_LABELS;
  protected readonly regions = REGIONS;
  protected readonly categories = computed(() => MANUAL_CATEGORIES.filter((c) => this.writable().includes(c)));

  protected readonly category = signal<DataCategory>('INCIDENT_RECORDING');
  protected readonly region = signal('');
  protected readonly description = signal('');
  protected readonly file = signal<File | null>(null);

  protected readonly progress = signal<number | null>(null);
  protected readonly note = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly result = signal<FileRecord | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly policy = computed(() => this.policies()[this.category()] ?? null);
  protected readonly tooLarge = computed(() => {
    const f = this.file();
    const p = this.policy();
    return !!f && !!p && f.size > p.maxFileSizeBytes;
  });

  private task: UploadTask | null = null;

  constructor() {
    effect(() => {
      const available = this.categories();
      if (available.length > 0 && !available.includes(this.category())) {
        this.category.set(available[0]);
      }
    });
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
    this.result.set(null);
    this.error.set(null);
    this.progress.set(null);
    this.note.set(null);
  }

  protected upload(): void {
    const file = this.file();
    if (!file || this.busy() || this.tooLarge()) return;

    this.busy.set(true);
    this.error.set(null);
    this.result.set(null);
    this.progress.set(0);
    this.note.set(null);

    const task = this.uploader.start(
      file,
      { category: this.category(), region: this.region(), description: this.description() },
      this.chunkSize(),
      (p) => {
        this.progress.set(p.total > 0 ? Math.floor((100 * p.loaded) / p.total) : 0);
        this.note.set(p.note);
      },
    );
    this.task = task;
    task.result.then(
      (stored) => {
        this.progress.set(100);
        this.note.set(null);
        this.result.set(stored);
        this.uploaded.emit(stored);
        this.busy.set(false);
      },
      (err) => {
        this.note.set(null);
        this.error.set(err instanceof UploadCancelled ? 'Upload anulat.' : errorMessage(err));
        this.busy.set(false);
      },
    );
  }

  protected cancel(): void {
    this.task?.cancel();
  }
}
