import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivate, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { AccountService } from './services/account.service';

@Injectable({ providedIn: 'root' })
export class AuthGuard implements CanActivate {
  constructor(private accountService: AccountService, private router: Router) {
  }

  canActivate(_route: ActivatedRouteSnapshot, state: RouterStateSnapshot): boolean | UrlTree {
    return this.accountService.isLoggedIn()
      ? true
      : this.router.createUrlTree(['/'], { queryParams: { returnUrl: state.url } });
  }
}
