import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { AfterViewInit, Component, ElementRef, inject, OnInit, ViewChild } from '@angular/core';
import { FormBuilder, FormControl, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { baseUrl, ref } from 'src/environments/environment';
import { IUserLogin } from 'src/model/IUserLogin';
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
              private http: HttpClient,
              private router: Router,
              private seoService: SeoService) {
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
    const body = new HttpParams()
      .set('ref', ref)
      .set('userEmail', email ?? '')
      .set('userPass', password ?? '')
      .set('face', 'no');

    this.http.post<IUserLogin>(baseUrl + 'userLogin.php', body.toString(), {
      headers: new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' })
    }).subscribe({
      next: response => {
        const user = response.user[0];
        if (user?.durum && user.bilgiler) {
          this.accountService.saveUserProfile(user.bilgiler, remember === true);
          void this.router.navigate(['/dashboard']);
        } else {
          this.rejectLogin();
        }
      },
      error: () => this.rejectLogin()
    });
  };

  private rejectLogin(): void {
    this.isFormDataValid = false;
    this.formLoginGroup.controls.password.reset('');
    this.viewEmailInput.nativeElement.focus();
  }
}
