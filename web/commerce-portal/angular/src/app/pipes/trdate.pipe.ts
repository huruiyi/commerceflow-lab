import { Pipe, PipeTransform } from '@angular/core';
import { I18nService } from '../services/i18n.service';

@Pipe({ standalone: false, name: 'trdate', pure: false })
export class TrdatePipe implements PipeTransform {
  constructor(private i18n: I18nService) {}

  transform(value: Date | string): string {
    return new Intl.DateTimeFormat(this.i18n.locale(), {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(value));
  }
}
