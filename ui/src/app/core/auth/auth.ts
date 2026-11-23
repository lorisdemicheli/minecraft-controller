import { Injectable, PLATFORM_ID, computed, inject, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpContext } from '@angular/common/http';
import { Router } from '@angular/router';
import { SKIP_ERROR_NOTIFICATION } from '../interceptors/http-context';
import { tap } from 'rxjs';
import { AUTHService } from '../api-client/api/auth.service';
import { LoginRequest } from '../api-client/model/login-request';
import { LoginResponse } from '../api-client/model/login-response';
import { Notifications } from '../notifications/notifications';

const TOKEN_KEY = 'auth_token';

/** Expiry (ms since epoch) read from the token's `exp` claim; null if unreadable. */
export function tokenExpiry(token: string): number | null {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const exp = JSON.parse(json).exp;
    return typeof exp === 'number' ? exp * 1000 : null;
  } catch {
    return null;
  }
}

/**
 * Session of the single admin. The JWT lives in localStorage (browser only); it is dropped when it
 * expires (timer on its `exp`) or when the API answers 401, so the app never stays half logged in.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private authApi = inject(AUTHService);
  private router = inject(Router);
  private notifications = inject(Notifications);
  private browser = isPlatformBrowser(inject(PLATFORM_ID));
  private expiryTimer: ReturnType<typeof setTimeout> | undefined;

  private _token = signal<string | null>(null);
  private _now = signal(Date.now());

  isAuthenticated = computed(() => {
    const token = this._token();
    if (!token) return false;
    const exp = tokenExpiry(token);
    return exp === null || exp > this._now();
  });

  constructor() {
    if (this.browser) {
      const stored = this.read();
      if (stored && !this.expired(stored)) {
        this.setToken(stored);
      } else if (stored) {
        this.write(null);
      }
    }
  }

  login(username: string, password: string) {
    const loginRequest: LoginRequest = { username, password };
    // the login form shows its own errors
    const context = new HttpContext().set(SKIP_ERROR_NOTIFICATION, true);
    return this.authApi.login(loginRequest, 'body', false, { context }).pipe(
      tap((response) => {
        const { token } = response as LoginResponse;
        if (!token) throw new Error('Login response without token');
        this.setToken(token);
      }),
    );
  }

  logout() {
    this.clear();
    this.router.navigate(['/login']);
  }

  /** The API refused the token (expired, revoked after a password change...). */
  sessionExpired() {
    if (!this._token()) return;
    this.clear();
    this.notifications.error('Sessione scaduta: accedi di nuovo');
    this.router.navigate(['/login'], { queryParams: { returnUrl: this.router.url } });
  }

  getToken(): string | null {
    const token = this._token();
    return token && !this.expired(token) ? token : null;
  }

  private setToken(token: string) {
    this._token.set(token);
    this._now.set(Date.now());
    this.write(token);
    clearTimeout(this.expiryTimer);
    const exp = tokenExpiry(token);
    if (exp !== null && this.browser) {
      // setTimeout overflows above ~24.8 days
      const delay = Math.min(Math.max(exp - Date.now(), 0), 2_147_483_000);
      this.expiryTimer = setTimeout(() => {
        this._now.set(Date.now());
        if (this.expired(token)) this.sessionExpired();
      }, delay);
    }
  }

  private clear() {
    clearTimeout(this.expiryTimer);
    this._token.set(null);
    this.write(null);
  }

  private expired(token: string): boolean {
    const exp = tokenExpiry(token);
    return exp !== null && exp <= Date.now();
  }

  private read(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  }

  private write(token: string | null) {
    if (!this.browser) return;
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token);
      else localStorage.removeItem(TOKEN_KEY);
    } catch {
      // storage disabled: the session lasts until the page is closed
    }
  }
}
