import {Directive, ElementRef, Input, OnInit} from '@angular/core';

@Directive({
  standalone: false,
  selector: '[appApptitle]'
})
export class ApptitleDirective implements OnInit {
  @Input() inputTitle = ''

  constructor(private element: ElementRef) {
    console.log('Directive called');
    //console.log(element);
  }

  ngAfterViewChecked(): void {
    this.element.nativeElement.innerHTML = "Orderss"
  }

  ngOnInit(): void {
    console.log(this.element.nativeElement.innerHTML);
    this.element.nativeElement.innerHTML = "Orderss"
  }
}
