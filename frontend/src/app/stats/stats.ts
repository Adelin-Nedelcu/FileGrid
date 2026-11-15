import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FileGridApi, errorMessage } from '../filegrid-api';
import { BytesPipe } from '../bytes.pipe';
import { ALL_CATEGORIES, CATEGORY_LABELS, DataCategory, Stats, regionName } from '../models';
import { CountyMap } from './county-map';

@Component({
  selector: 'app-stats',
  imports: [FormsModule, DatePipe, DecimalPipe, BytesPipe, CountyMap],
  templateUrl: './stats.html',
  styleUrl: './stats.css',
})
export class StatsPage {
  private readonly api = inject(FileGridApi);

  readonly canRunBenchmark = input(false);

  protected readonly labels = CATEGORY_LABELS;
  protected readonly regionName = regionName;
  protected readonly windows = [
    { hours: 1, label: 'Ultima oră' },
    { hours: 6, label: 'Ultimele 6 ore' },
    { hours: 24, label: 'Ultimele 24 de ore' },
    { hours: 168, label: 'Ultimele 7 zile' },
  ];
  protected readonly consumerLabels: Record<string, string> = {
    CASNIC: 'Casnic',
    COMERCIAL: 'Comercial',
    INDUSTRIAL: 'Industrial',
  };

  protected readonly hours = signal(6);
  protected readonly stats = signal<Stats | null>(null);
  protected readonly loading = signal(false);
  protected readonly measuring = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly maxAnomalies = computed(() =>
    Math.max(1, ...(this.stats()?.network.regions.map((r) => r.anomaliesPer1000) ?? [])),
  );
  protected readonly maxIncidents = computed(() =>
    Math.max(1, ...(this.stats()?.network.regions.map((r) => r.incidents) ?? [])),
  );

  protected readonly timelineCategories = computed<DataCategory[]>(() => {
    const buckets = this.stats()?.timeline.buckets ?? [];
    return ALL_CATEGORIES.filter((c) => buckets.some((b) => b.categories[c].stored > 0));
  });

  protected readonly timelineMax = computed(() => {
    const max: Partial<Record<DataCategory, number>> = {};
    for (const b of this.stats()?.timeline.buckets ?? []) {
      for (const c of ALL_CATEGORIES) max[c] = Math.max(max[c] ?? 1, b.categories[c].stored);
    }
    return max;
  });

  protected readonly unitLabels: Record<string, string> = {
    '5min': 'Interval de 5 minute',
    '30min': 'Jumătate de oră',
    hour: 'Oră',
    day: 'Zi',
  };

  protected bucketFormat(unit: string): string {
    return unit === 'day' ? 'dd.MM.yyyy' : 'dd.MM HH:mm';
  }

  constructor() {
    effect(() => {
      this.hours();
      untracked(() => this.load());
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.stats(this.hours()).subscribe({
      next: (stats) => {
        this.stats.set(stats);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err));
        this.loading.set(false);
      },
    });
  }

  protected measure(): void {
    this.measuring.set(true);
    this.error.set(null);
    this.api.runBenchmark(this.hours()).subscribe({
      next: (stats) => {
        this.stats.set(stats);
        this.measuring.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err));
        this.measuring.set(false);
      },
    });
  }

  protected ms(value: number | null | undefined): string {
    if (value === null || value === undefined) return '—';
    if (value >= 1000) return `${(value / 1000).toFixed(1).replace('.', ',')} s`;
    return `${value.toFixed(value < 10 ? 1 : 0).replace('.', ',')} ms`;
  }

  protected width(value: number, max: number): string {
    return `${Math.round((value * 75) / max)}%`;
  }
}
