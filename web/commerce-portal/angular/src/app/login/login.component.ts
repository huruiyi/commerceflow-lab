import { AfterViewInit, ChangeDetectorRef, Component, ElementRef, inject, OnInit, ViewChild } from '@angular/core';
import { FormBuilder, FormControl, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs';
import { AccountService } from '../services/account.service';
import { SeoService } from '../services/seo.service';
import { I18nService } from '../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css'],
})
export class LoginComponent implements OnInit, AfterViewInit {
  @ViewChild('refEmail') viewEmailInput!: ElementRef<HTMLInputElement>;

  isFormSubmitted = false;
  isFormDataValid = true;
  isSubmitting = false;
  loginMessage = '';
  private readonly formBuilder = inject(FormBuilder);

  formLoginGroup = this.formBuilder.group({
    email: new FormControl('', [Validators.required, Validators.email]),
    password: new FormControl('', [Validators.required, Validators.minLength(3)]),
    remember: new FormControl(false),
  });

  constructor(
    private accountService: AccountService,
    private router: Router,
    private seoService: SeoService,
    private changeDetector: ChangeDetectorRef,
    public i18n: I18nService,
  ) {
    this.seoService.setTitle(this.i18n.t('loginTitle'));
    this.seoService.setMeta(this.i18n.t('loginTitle'));
  }

  ngOnInit(): void {
    if (this.accountService.isLoggedIn()) {
      void this.router.navigate(['/dashboard']);
    }
  }

  ngAfterViewInit(): void {
    this.viewEmailInput.nativeElement.focus();
  }

  formOnSubmit = (): void => {
    this.isFormSubmitted = true;
    this.isFormDataValid = true;
    if (this.formLoginGroup.invalid) {
      return;
    }

    this.isSubmitting = true;
    this.loginMessage = '';
    const { email, password, remember } = this.formLoginGroup.getRawValue();
    this.accountService
      .login(email ?? '', password ?? '', remember === true)
      .pipe(
        finalize(() => {
          this.isSubmitting = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: () => void this.router.navigate(['/dashboard']),
        error: (error) => this.rejectLogin(error),
      });
  };

  private rejectLogin(error: HttpErrorResponse): void {
    this.isFormDataValid = false;
    if (error.status === 429) {
      this.loginMessage = this.i18n.t('loginRateLimited');
    } else if (error.status === 0 || error.status >= 500) {
      this.loginMessage = this.i18n.t('loginUnavailable');
    } else {
      this.loginMessage = this.i18n.t('loginRejected');
    }
    this.formLoginGroup.controls.password.reset('');
    this.viewEmailInput.nativeElement.focus();
    this.changeDetector.markForCheck();
  }
}
