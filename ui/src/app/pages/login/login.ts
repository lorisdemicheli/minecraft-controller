import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { Auth } from '../../core/auth/auth';
import { Theme } from '../../core/theme/theme';
import { BrandMark } from '../../shared/brand-mark';

function loginError(err: HttpErrorResponse): string {
  switch (err.status) {
    case 401:
      return 'Credenziali non valide';
    case 429:
      return 'Troppi tentativi falliti: riprova tra qualche minuto';
    case 0:
      return 'Backend non raggiungibile, riprova';
    default:
      return 'Errore di connessione, riprova';
  }
}

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, MatIconModule, BrandMark],
  templateUrl: './login.html',
  styleUrl: './login.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Login {
  private auth = inject(Auth);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  readonly theme = inject(Theme);

  readonly form = inject(FormBuilder).nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  private _loading = signal(false);
  private _error = signal('');
  private _hidePassword = signal(true);

  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();
  readonly hidePassword = this._hidePassword.asReadonly();

  togglePassword() {
    this._hidePassword.update((hidden) => !hidden);
  }

  submit() {
    if (this.form.invalid || this._loading()) return;

    this._loading.set(true);
    this._error.set('');

    const { username, password } = this.form.getRawValue();
    this.auth.login(username, password).subscribe({
      next: () => {
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
        // only paths of this app (no open redirect)
        const target = returnUrl?.startsWith('/') && !returnUrl.startsWith('//') ? returnUrl : '/servers';
        this.router.navigateByUrl(target);
      },
      error: (err: HttpErrorResponse) => {
        this._error.set(loginError(err));
        this._loading.set(false);
      },
    });
  }
}
