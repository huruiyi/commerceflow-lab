import { AfterViewInit, ChangeDetectorRef, Component, ElementRef, inject, OnInit, ViewChild } from '@angular/core';
import { FormBuilder, FormControl, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AccountService } from '../services/account.service';
import { SeoService } from '../services/seo.service';

@Component({
  standalone: false,
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css']
})
export class LoginComponent implements OnInit, AfterViewInit {
  @ViewChild('refEmail') viewEmailInput!: ElementRef<HTMLInputElement>;

  isFormSubmitted = false;
  isFormDataValid = true;
  private readonly formBuilder = inject(FormBuilder);

  formLoginGroup = this.formBuilder.group({
    email: new FormControl('', [Validators.required, Validators.email]),
    password: new FormControl('', [Validators.required, Validators.minLength(3)]),
    remember: new FormControl(false)
  });

  constructor(private accountService: AccountService,
              private router: Router,
              private seoService: SeoService,
              private changeDetector: ChangeDetectorRef) {
    this.seoService.setTitle('User Login');
    this.seoService.setMeta('Login Page Detail');
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

    const { email, password, remember } = this.formLoginGroup.getRawValue();
    this.accountService.login(email ?? '', password ?? '', remember === true).subscribe({
      next: () => void this.router.navigate(['/dashboard']),
      error: () => this.rejectLogin()
    });
  };

  private rejectLogin(): void {
    this.isFormDataValid = false;
    this.formLoginGroup.controls.password.reset('');
    this.viewEmailInput.nativeElement.focus();
    this.changeDetector.markForCheck();
  }
}
