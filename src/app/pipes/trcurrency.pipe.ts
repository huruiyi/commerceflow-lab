import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  standalone: false,
  name: 'trcurrency'
})
export class TrcurrencyPipe implements PipeTransform {

  transform(value: string, ...args: unknown[]): string {
    return value + "₺";
  }

}
