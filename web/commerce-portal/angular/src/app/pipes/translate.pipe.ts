import { Pipe, PipeTransform } from '@angular/core';
import { I18nService } from '../services/i18n.service';

@Pipe({ standalone: false, name: 't', pure: false })
export class TranslatePipe implements PipeTransform {
  constructor(private i18n: I18nService) {}

  transform(key: string) {
    return this.i18n.t(key);
  }
}
