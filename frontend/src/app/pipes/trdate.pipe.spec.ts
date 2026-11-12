import { TrdatePipe } from './trdate.pipe';
import { I18nService } from '../services/i18n.service';

describe('TrdatePipe', () => {
  it('create an instance', () => {
    const pipe = new TrdatePipe(new I18nService());
    expect(pipe).toBeTruthy();
  });
});
