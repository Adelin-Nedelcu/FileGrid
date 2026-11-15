import { Component, effect, inject, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FileGridApi, errorMessage } from '../filegrid-api';
import { AuditEntry, CATEGORY_LABELS } from '../models';

@Component({
  selector: 'app-audit',
  imports: [FormsModule, DatePipe],
  templateUrl: './audit.html',
  styleUrl: './audit.css',
})
export class Audit {
  private readonly api = inject(FileGridApi);

  protected readonly labels = CATEGORY_LABELS;
  protected readonly actions: Record<string, string> = {
    UPLOAD: 'Încărcare',
    DOWNLOAD: 'Descărcare',
    VERIFY: 'Verificare',
    ACCESS_DENIED: 'Acces refuzat',
  };
  protected readonly actionCodes = Object.keys(this.actions);
  protected readonly pageSize = 25;

  protected readonly action = signal('');
  protected readonly query = signal('');
  protected readonly page = signal(0);
  protected readonly items = signal<AuditEntry[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
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
    this.api.audit({ action: this.action(), q: this.query(), page: this.page(), size: this.pageSize }).subscribe({
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

  protected lastPage(): number {
    return Math.max(0, Math.ceil(this.total() / this.pageSize) - 1);
  }
}
