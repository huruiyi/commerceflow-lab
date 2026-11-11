import { Component, OnInit } from '@angular/core';
import { AccountService } from 'src/app/services/account.service';
import { UserProfile } from 'src/model/IUserLogin';
import { I18nService } from '../../services/i18n.service';
import { Router } from '@angular/router';

@Component({
  standalone: false,
  selector: 'app-navbar',
  templateUrl: './navbar.component.html',
  styleUrls: ['./navbar.component.css']
})
export class NavbarComponent implements OnInit {
  searchText = ''
  bilgiler: UserProfile = {
    id: 0,
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    role: ''
  }

  constructor(private accountService :AccountService,public i18n:I18nService,private router:Router) {
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

  get isProductPage(): boolean { return this.router.url.startsWith('/dashboard') }

  get accountDisplayName(): string {
    if (this.bilgiler.email.endsWith('@example.com') && this.bilgiler.firstName.toLowerCase() === 'demo') {
      return this.i18n.t(this.bilgiler.role === 'CUSTOMER' ? 'demoCustomerName' : this.bilgiler.role === 'MERCHANT' ? 'demoMerchantName' : 'demoAdminName')
    }
    return [this.bilgiler.firstName, this.bilgiler.lastName].filter(Boolean).join(' ') || this.bilgiler.email || this.i18n.t('account')
  }

  get accountInitial(): string { return this.accountDisplayName.trim().slice(0, 1).toUpperCase() || '?' }

  search(): void {
    const query = this.searchText.trim()
    void this.router.navigate(['/dashboard'], {queryParams: query ? {q: query} : {}})
  }
}
