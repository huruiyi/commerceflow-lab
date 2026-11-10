import { Injectable } from '@angular/core';
import { Bilgiler } from '../../model/IUserLogin';

const STORAGE_KEY = 'demo-user-profile';

@Injectable({ providedIn: 'root' })
export class AccountService {
  loginControl(): Bilgiler | null {
    const value = sessionStorage.getItem(STORAGE_KEY) ?? localStorage.getItem(STORAGE_KEY);
    if (!value) {
      return null;
    }
    try {
      return JSON.parse(value) as Bilgiler;
    } catch {
      this.clearLocalState();
      return null;
    }
  }

  saveUserProfile(user: Bilgiler, remember: boolean): void {
    this.clearLocalState();
    const serialized = JSON.stringify(user);
    if (remember) {
      localStorage.setItem(STORAGE_KEY, serialized);
    } else {
      sessionStorage.setItem(STORAGE_KEY, serialized);
    }
  }

  isLoggedIn(): boolean {
    return this.loginControl() !== null;
  }

  logout(): void {
    this.clearLocalState();
    window.location.assign('/');
  }

  private clearLocalState(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(STORAGE_KEY);
  }
}
