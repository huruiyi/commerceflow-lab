import { formatDate, NgLocalization } from '@angular/common';
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  standalone: false,
  name: 'trdate'
})
export class TrdatePipe implements PipeTransform {

  plural_locale_tr(val: number): number {
    const n = val;

    if (n === 1)
        return 1;
    return 5;
    }

  transform(value: Date, ...args: unknown[]): Date {
    return value
  }

}
