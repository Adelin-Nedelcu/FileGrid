import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FileGridApi, errorMessage } from '../filegrid-api';
import { BytesPipe } from '../bytes.pipe';
import { ALL_CATEGORIES, CATEGORY_LABELS, DataCategory, FileRecord, REGIONS, VerificationResult, regionName } from '../models';

@Component({
  selector: 'app-file-list',
  imports: [FormsModule, BytesPipe, DatePipe],
  templateUrl: './file-list.html',
  styleUrl: './file-list.css',
})
export class FileList {
  private readonly api = inject(FileGridApi);

  readonly refresh = input(0);

  protected readonly categories = ALL_CATEGORIES;
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

  constructor() {
    effect(() => {
      this.refresh();
      this.page();
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
    this.loading.set(true);
    this.error.set(null);
    this.api
      .search({
        category: this.category(),
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

  protected contentUrl(id: string): string {
    return this.api.contentUrl(id);
  }

  protected lastPage(): number {
    return Math.max(0, Math.ceil(this.total() / this.pageSize) - 1);
  }
}
