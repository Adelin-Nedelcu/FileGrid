import { Component, computed, input, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { REGIONS, RegionStat } from '../models';
import { COUNTY_SHAPES, MAP_HEIGHT, MAP_WIDTH } from './county-shapes';

const SERVED = new Set(REGIONS.flatMap((g) => g.counties.map((c) => c.code)));
const STEPS = ['#fdece9', '#f9c4b8', '#f19a85', '#e06c52', '#c4432d', '#9a2414'];

interface CountyView {
  code: string;
  name: string;
  d: string;
  x: number;
  y: number;
  served: boolean;
  incidents: number;
  anomalies: number | null;
  fill: string;
}

@Component({
  selector: 'app-county-map',
  imports: [DecimalPipe],
  templateUrl: './county-map.html',
  styleUrl: './county-map.css',
})
export class CountyMap {
  readonly regions = input<RegionStat[]>([]);

  protected readonly width = MAP_WIDTH;
  protected readonly height = MAP_HEIGHT;
  protected readonly hovered = signal<string | null>(null);

  protected readonly max = computed(() => Math.max(0, ...this.regions().map((r) => r.incidents)));
  protected readonly total = computed(() => this.regions().reduce((sum, r) => sum + r.incidents, 0));

  protected readonly counties = computed<CountyView[]>(() => {
    const byCode = new Map(this.regions().map((r) => [r.region, r]));
    const max = this.max();
    return COUNTY_SHAPES.map((shape) => {
      const stat = byCode.get(shape.code);
      const served = SERVED.has(shape.code);
      const incidents = stat?.incidents ?? 0;
      return {
        ...shape,
        served,
        incidents,
        anomalies: stat && stat.readings > 0 ? stat.anomaliesPer1000 : null,
        fill: served ? this.color(incidents, max) : 'var(--map-outside)',
      };
    });
  });

  protected readonly legend = computed(() => {
    const max = this.max();
    if (max === 0) return [];
    const n = STEPS.length - 1;
    return STEPS.map((color, i) => {
      if (i === 0) return { color, from: 0, to: 0 };
      return { color, from: Math.floor((max * (i - 1)) / n) + 1, to: Math.floor((max * i) / n) };
    })
      .filter((step) => step.from <= step.to)
      .map((step) => ({ color: step.color, label: step.from === step.to ? `${step.from}` : `${step.from}–${step.to}` }));
  });

  protected readonly selected = computed(() => this.counties().find((c) => c.code === this.hovered()) ?? null);

  private color(value: number, max: number): string {
    if (value === 0 || max === 0) return STEPS[0];
    const index = Math.min(STEPS.length - 1, Math.max(1, Math.ceil((value / max) * (STEPS.length - 1))));
    return STEPS[index];
  }
}
