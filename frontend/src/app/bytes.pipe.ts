import { Pipe, PipeTransform } from '@angular/core';

@Pipe({ name: 'bytes' })
export class BytesPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    if (value == null) return '—';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let v = value;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i++;
    }
    return `${v.toFixed(i === 0 ? 0 : 1).replace('.', ',')} ${units[i]}`;
  }
}
