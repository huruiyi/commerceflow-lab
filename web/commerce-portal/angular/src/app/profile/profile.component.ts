import {Component, inject} from '@angular/core';
import {UserProfile} from 'src/model/IUserLogin';
import {AccountService} from '../services/account.service';
import {I18nService} from '../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-profile',
  templateUrl: './profile.component.html',
  styleUrls: ['./profile.component.css']
})
export class ProfileComponent {
  private readonly accountService = inject(AccountService);
  readonly i18n = inject(I18nService);
  readonly profile = this.accountService.userProfile;

  displayName(user: UserProfile): string {
    if (user.email.endsWith('@example.com') && user.firstName.toLowerCase() === 'demo') {
      return this.i18n.t(user.role === 'CUSTOMER' ? 'demoCustomerName' : user.role === 'MERCHANT' ? 'demoMerchantName' : 'demoAdminName');
    }
    return [user.firstName, user.lastName].filter(Boolean).join(' ') || user.email;
  }

  initials(user: UserProfile): string {
    const value = this.displayName(user).trim();
    return value ? value.slice(0, 1).toUpperCase() : '?';
  }
}
