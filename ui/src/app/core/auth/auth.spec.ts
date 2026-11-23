import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Auth, tokenExpiry } from './auth';
import { Configuration } from '../api-client';
import { Notifications } from '../notifications/notifications';
import { fakeToken } from '../../testing/jwt';

describe('Auth', () => {
  const notifications = { error: vi.fn(), info: vi.fn() };

  function setup() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Configuration, useValue: new Configuration({ basePath: 'http://api' }) },
        { provide: Notifications, useValue: notifications },
      ],
    });
    return TestBed.inject(Auth);
  }

  beforeEach(() => {
    localStorage.clear();
    notifications.error.mockReset();
  });

  afterEach(() => vi.useRealTimers());

  it('reads the expiry from the token', () => {
    expect(tokenExpiry(fakeToken(1000))).toBe(1_000_000);
    expect(tokenExpiry('garbage')).toBeNull();
  });

  it('login stores the token and authenticates', () => {
    const auth = setup();
    const token = fakeToken(Math.floor(Date.now() / 1000) + 3600);
    auth.login('admin', 'pw').subscribe();
    TestBed.inject(HttpTestingController).expectOne('http://api/login').flush({ token });
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.getToken()).toBe(token);
    expect(localStorage.getItem('auth_token')).toBe(token);
  });

  it('ignores an expired stored token', () => {
    localStorage.setItem('auth_token', fakeToken(Math.floor(Date.now() / 1000) - 10));
    const auth = setup();
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getToken()).toBeNull();
    expect(localStorage.getItem('auth_token')).toBeNull();
  });

  it('logs out by itself when the token expires', () => {
    vi.useFakeTimers();
    const auth = setup();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    auth.login('admin', 'pw').subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne('http://api/login')
      .flush({ token: fakeToken(Math.floor(Date.now() / 1000) + 60) });
    expect(auth.isAuthenticated()).toBe(true);

    vi.advanceTimersByTime(61_000);

    expect(auth.isAuthenticated()).toBe(false);
    expect(notifications.error).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.anything());
  });

  it('sessionExpired clears the session once', () => {
    localStorage.setItem('auth_token', fakeToken(Math.floor(Date.now() / 1000) + 3600));
    const auth = setup();
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    auth.sessionExpired();
    auth.sessionExpired();
    expect(auth.isAuthenticated()).toBe(false);
    expect(notifications.error).toHaveBeenCalledTimes(1);
  });
});
