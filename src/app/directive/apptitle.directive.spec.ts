import { ApptitleDirective } from './apptitle.directive';
import { ElementRef } from '@angular/core';

describe('ApptitleDirective', () => {
  it('should create an instance', () => {
    const directive = new ApptitleDirective(new ElementRef(document.createElement('div')));
    expect(directive).toBeTruthy();
  });
});
