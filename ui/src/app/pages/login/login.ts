import { Component } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Auth } from '../../core/auth/auth';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatInputModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule
  ],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  form: FormGroup;
  loading = false;
  error = '';
  hidePassword = true;

  constructor(
    private fb: FormBuilder,
    private auth: Auth,
    private router: Router
  ) {
    this.form = this.fb.group({
      username: ['', Validators.required],
      password: ['', Validators.required]
    });
  }

  submit() {
  if (this.form.invalid) return;

  this.loading = true;
  this.error = '';

  const { username, password } = this.form.value;
  console.log('Invio login...', { username });

  this.auth.login(username, password).subscribe({
    next: (res) => {
      console.log('Login ok, risposta:', res);       // ← vedi la risposta raw
      this.router.navigate(['/servers']);
    },
    error: (err) => {
      console.error('Errore login:', err);            // ← vedi l'errore completo
      this.error = err.status === 401
        ? 'Credenziali non valide'
        : 'Errore di connessione, riprova';
      this.loading = false;
    }
  });
}
}