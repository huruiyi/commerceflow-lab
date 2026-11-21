import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivate, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { AccountService } from './services/account.service';
import { Observable, map } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class AuthGuard implements CanActivate {
  constructor(
    private accountService: AccountService,
    private router: Router,
  ) {}

  canActivate(route: ActivatedRouteSnapshot, state: RouterStateSnapshot): Observable<boolean | UrlTree> {
    return this.accountService.validateSession().pipe(
      map((profile) => {
        if (!profile) return this.router.createUrlTree(['/'], { queryParams: { returnUrl: state.url } });
        const roles = route.data['roles'] as string[] | undefined;
        const invoiceMode = route.data['invoiceMode'];
        const authorizedRole = !roles?.length || roles.includes(profile.role);
        const authorizedInvoice =
          !invoiceMode || (profile.invoiceEnabled && (invoiceMode !== 'platform' || profile.invoiceOperations));
        return authorizedRole && authorizedInvoice ? true : this.router.createUrlTree(['/dashboard']);
      }),
    );
  }
}
