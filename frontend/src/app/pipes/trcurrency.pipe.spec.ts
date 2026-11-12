import { TrcurrencyPipe } from './trcurrency.pipe';
import { I18nService } from '../services/i18n.service';

describe('TrcurrencyPipe', () => {
  it('create an instance', () => {
    const pipe = new TrcurrencyPipe(new I18nService());
    expect(pipe).toBeTruthy();
  });
});
