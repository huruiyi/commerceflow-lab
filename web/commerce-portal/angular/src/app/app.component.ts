import {Component, inject} from '@angular/core';
import {AccountService} from './services/account.service';

@Component({
  standalone: false,
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent {
  title = 'CommerceFlow Lab';
  private readonly accountService = inject(AccountService);
  readonly userProfile = this.accountService.userProfile;
}
