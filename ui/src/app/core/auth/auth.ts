import { Injectable, signal, computed } from '@angular/core';
import { Router } from '@angular/router';
import { tap } from 'rxjs';
import { AUTHService } from '../api-client/api/auth.service';
import { LoginRequest } from '../api-client/model/login-request';
import { LoginResponse } from '../api-client';

@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly TOKEN_KEY = 'auth_token';

  private _token = signal<string | null>(
    typeof window !== 'undefined' ? localStorage.getItem(this.TOKEN_KEY) : null
  );

  isAuthenticated = computed(() => !!this._token());

  constructor(
    private authApiService: AUTHService,
    private router: Router
  ) {}

  login(username: string, password: string) {
    const loginRequest: LoginRequest = { username, password };

    return this.authApiService.login(loginRequest).pipe(
      tap((response: LoginResponse) => {
        const token = response.token!;  // ← semplificato, sai già il campo
        localStorage.setItem(this.TOKEN_KEY, token);
        this._token.set(token);
      })
    );
  }

  logout() {
    localStorage.removeItem(this.TOKEN_KEY);
    this._token.set(null);
    this.router.navigate(['/login']);
  }

  getToken(): string | null {
    return this._token();
  }
}