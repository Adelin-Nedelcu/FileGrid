import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FileGridApi, errorMessage } from '../filegrid-api';
import { BytesPipe } from '../bytes.pipe';
import { canResumeDownloads, resumableDownload } from '../downloader';
import { ALL_CATEGORIES, CATEGORY_LABELS, DataCategory, MANUAL_CATEGORIES, FileRecord, REGIONS, VerificationResult, regionName } from '../models';

@Component({
  selector: 'app-file-list',
  imports: [FormsModule, BytesPipe, DatePipe],
  templateUrl: './file-list.html',
  styleUrl: './file-list.css',
})
export class FileList {
  private readonly api = inject(FileGridApi);

  readonly refresh = input(0);
  readonly readable = input<DataCategory[]>([]);

  protected readonly categories = computed(() => ALL_CATEGORIES.filter((c) => this.readable().includes(c)));
  protected readonly manual = computed(() => MANUAL_CATEGORIES.filter((c) => this.readable().includes(c)));
  protected readonly labels = CATEGORY_LABELS;
  protected readonly regions = REGIONS;
  protected readonly regionName = regionName;
  protected readonly pageSize = 20;

  protected readonly category = signal<DataCategory | ''>('');
  protected readonly region = signal('');
  protected readonly query = signal('');
  protected readonly page = signal(0);

  protected readonly items = signal<FileRecord[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly verifications = signal<Record<string, VerificationResult | 'pending' | string>>({});
  protected readonly downloads = signal<Record<string, { text: string; failed: boolean; done: boolean }>>({});
  protected readonly resumable = canResumeDownloads();

  constructor() {
    effect(() => {
      this.refresh();
      this.page();
      this.readable();
      untracked(() => this.load());
    });
  }

  protected search(): void {
    if (this.page() === 0) {
      this.load();
    } else {
      this.page.set(0);
    }
  }

  protected load(): void {
    const selected = this.category();
    const categories = selected ? [selected] : this.manual().length > 0 ? this.manual() : this.categories();
    if (categories.length === 0) {
      this.items.set([]);
      this.total.set(0);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.api
      .search({
        categories,
        region: this.region(),
        q: this.query(),
        page: this.page(),
        size: this.pageSize,
      })
      .subscribe({
        next: (result) => {
          this.items.set(result.items);
          this.total.set(result.total);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(errorMessage(err));
          this.loading.set(false);
        },
      });
  }

  protected verify(file: FileRecord): void {
    this.verifications.update((v) => ({ ...v, [file.id]: 'pending' }));
    this.api.verify(file.id).subscribe({
      next: (result) => this.verifications.update((v) => ({ ...v, [file.id]: result })),
      error: (err) => this.verifications.update((v) => ({ ...v, [file.id]: errorMessage(err) })),
    });
  }

  protected verification(id: string): VerificationResult | 'pending' | string | undefined {
    return this.verifications()[id];
  }

  protected isResult(v: unknown): v is VerificationResult {
    return typeof v === 'object' && v !== null && 'match' in v;
  }

  protected isDownloading(id: string): boolean {
    const state = this.downloads()[id];
    return !!state && !state.failed && !state.done;
  }

  protected download(file: FileRecord): void {
    const set = (text: string, failed = false, done = false) =>
      this.downloads.update((d) => ({ ...d, [file.id]: { text, failed, done } }));
    const size = file.sizeBytes ?? 0;
    set('Alegeți unde se salvează…');
    resumableDownload(this.api.contentUrl(file.id), file.name, size, (p) =>
      set(p.note ?? `${p.total > 0 ? Math.floor((p.loaded * 100) / p.total) : 100}% descărcat`),
    )
      .then((saved) => {
        if (saved) {
          set('Descărcat complet', false, true);
        } else {
          this.downloads.update((d) => {
            const rest = { ...d };
            delete rest[file.id];
            return rest;
          });
        }
      })
      .catch((err) => set(errorMessage(err), true));
  }

  protected contentUrl(id: string): string {
    return this.api.contentUrl(id);
  }

  protected lastPage(): number {
    return Math.max(0, Math.ceil(this.total() / this.pageSize) - 1);
  }
}
