import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { Login } from './login';
import { Auth } from '../../core/auth/auth';
import { testProviders } from '../../testing/providers';

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let component: Login;
  const auth = { login: vi.fn() };

  beforeEach(async () => {
    auth.login.mockReset();
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [...testProviders(), { provide: Auth, useValue: auth }],
    }).compileComponents();
    fixture = TestBed.createComponent(Login);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('does not submit an empty form', () => {
    component.submit();
    expect(auth.login).not.toHaveBeenCalled();
  });

  it('navigates to the servers after login', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    auth.login.mockReturnValue(of({ token: 't' }));
    component.form.setValue({ username: 'admin', password: 'pw' });
    component.submit();
    expect(navigate).toHaveBeenCalledWith('/servers');
  });

  it('shows a specific message for wrong credentials and throttling', () => {
    component.form.setValue({ username: 'admin', password: 'bad' });
    auth.login.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));
    component.submit();
    expect(component.error()).toBe('Credenziali non valide');
    expect(component.loading()).toBe(false);

    auth.login.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 429 })));
    component.submit();
    expect(component.error()).toContain('Troppi tentativi');
  });
});
