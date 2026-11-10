import { Component, OnInit } from '@angular/core';
import { AccountService } from 'src/app/services/account.service';
import { UserProfile } from 'src/model/IUserLogin';
import { I18nService } from '../../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-navbar',
  templateUrl: './navbar.component.html',
  styleUrls: ['./navbar.component.css']
})
export class NavbarComponent implements OnInit {
  bilgiler: UserProfile = {
    id: 0,
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    role: ''
  }

  constructor(private accountService :AccountService,public i18n:I18nService) {
    const userInfo = this.accountService.loginControl()

    if (userInfo) {
      this.bilgiler = userInfo
    }
  }

  ngOnInit(): void {
  }

  logout(): void {
    this.accountService.logout()
  }
}
