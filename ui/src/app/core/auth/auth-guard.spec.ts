import { TestBed } from '@angular/core/testing';
import { CanActivateFn, UrlTree, provideRouter } from '@angular/router';
import { authGuard } from './auth-guard';
import { Auth } from './auth';

describe('authGuard', () => {
  const run = (authenticated: boolean) => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: Auth, useValue: { isAuthenticated: () => authenticated } }],
    });
    const guard: CanActivateFn = (...args) => TestBed.runInInjectionContext(() => authGuard(...args));
    return guard({} as never, {} as never);
  };

  it('lets authenticated users in', () => {
    expect(run(true)).toBe(true);
  });

  it('redirects to the login otherwise', () => {
    const result = run(false) as UrlTree;
    expect(result.toString()).toBe('/login');
  });
});
