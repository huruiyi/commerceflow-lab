import { Component, Input, OnInit } from '@angular/core';
import { IUser } from 'src/model/IUser';

@Component({
  standalone: false,
  selector: 'app-useritem',
  templateUrl: './useritem.component.html',
  styleUrls: ['./useritem.component.css']
})
export class UseritemComponent implements OnInit {
  @Input() userItem?: IUser;

  constructor() { }

  ngOnInit(): void {
  }

  fncBtnDetail() {
    alert("Detail clicked for " + this.userItem?.id);
  }

}
