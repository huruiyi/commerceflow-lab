import {Component, Input, OnInit} from '@angular/core';
import {IUser} from 'src/model/IUser';
import {I18nService} from '../../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-useritem',
  templateUrl: './useritem.component.html',
  styleUrls: ['./useritem.component.css']
})
export class UseritemComponent implements OnInit {
  @Input() userItem?: IUser;

  constructor(private i18n: I18nService) {
  }

  ngOnInit(): void {
  }

  fncBtnDetail() {
    alert(this.i18n.t('detailClicked', {id: this.userItem?.id ?? ''}));
  }

}
