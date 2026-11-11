import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { catchError, finalize, map, Observable, of, switchMap, tap } from 'rxjs';
import { LoginResponse, UserProfile } from '../../model/IUserLogin';

const STORAGE_KEY = 'demo-user-profile';

@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly profileState = signal<UserProfile | null>(this.readStoredProfile());
  readonly userProfile = this.profileState.asReadonly();

  constructor(private http: HttpClient) {
  }

  login(email: string, password: string, remember: boolean): Observable<UserProfile> {
    return this.ensureCsrf().pipe(
      switchMap(() => this.http.post<LoginResponse>('/api/auth/login', { email, password, remember })),
      tap(response => this.saveUserProfile(response.user, remember)),
      map(response => response.user)
    );
  }

  loginControl(): UserProfile | null {
    return this.profileState();
  }

  private readStoredProfile(): UserProfile | null {
    const value = sessionStorage.getItem(STORAGE_KEY) ?? localStorage.getItem(STORAGE_KEY);
    if (!value) {
      return null;
    }
    try {
      return JSON.parse(value) as UserProfile;
    } catch {
      sessionStorage.removeItem(STORAGE_KEY);
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
  }

  isLoggedIn(): boolean {
    return this.loginControl() !== null;
  }

  validateSession(): Observable<UserProfile | null> {
    return this.http.get<UserProfile>('/api/auth/me').pipe(
      tap(profile => this.saveValidatedProfile(profile)),
      map(profile => profile as UserProfile | null),
      catchError(() => {
        this.clearLocalState();
        return of(null);
      })
    );
  }

  logout(): void {
    this.ensureCsrf().pipe(
      switchMap(() => this.http.post<void>('/api/auth/logout', {})),
      finalize(() => {
        this.clearLocalState();
        window.location.assign('/');
      })
    ).subscribe({ error: () => undefined });
  }

  private ensureCsrf(): Observable<{ headerName: string; token: string }> {
    return this.http.get<{ headerName: string; token: string }>('/api/auth/csrf');
  }

  private saveUserProfile(user: UserProfile, remember: boolean): void {
    this.clearLocalState();
    const serialized = JSON.stringify(user);
    if (remember) {
      localStorage.setItem(STORAGE_KEY, serialized);
    } else {
      sessionStorage.setItem(STORAGE_KEY, serialized);
    }
    this.profileState.set(user);
  }

  private saveValidatedProfile(user: UserProfile): void {
    const persistent = localStorage.getItem(STORAGE_KEY) !== null;
    this.saveUserProfile(user, persistent);
  }

  private clearLocalState(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(STORAGE_KEY);
    this.profileState.set(null);
  }
}
