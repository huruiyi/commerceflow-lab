import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  standalone: false,
  name: 'trcurrency'
})
export class TrcurrencyPipe implements PipeTransform {

  transform(value: string | number, ...args: unknown[]): string {
    return value + "₺";
  }

}
