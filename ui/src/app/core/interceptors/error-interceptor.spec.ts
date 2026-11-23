import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  HttpContext,
  HttpErrorResponse,
  HttpHeaders,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { errorInterceptor, errorMessage } from './error-interceptor';
import { SKIP_ERROR_NOTIFICATION } from './http-context';
import { Auth } from '../auth/auth';
import { Configuration } from '../api-client';
import { Notifications } from '../notifications/notifications';

describe('errorInterceptor', () => {
  const auth = { sessionExpired: vi.fn() };
  const notifications = { error: vi.fn(), info: vi.fn() };

  beforeEach(() => {
    auth.sessionExpired.mockReset();
    notifications.error.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: Auth, useValue: auth },
        { provide: Notifications, useValue: notifications },
        { provide: Configuration, useValue: new Configuration({ basePath: 'http://api' }) },
      ],
    });
  });

  function fail(status: number, body: object | null = null, context?: HttpContext, url = 'http://api/x') {
    let error: unknown;
    TestBed.inject(HttpClient).get(url, { context }).subscribe({ error: (e) => (error = e) });
    TestBed.inject(HttpTestingController).expectOne(url).flush(body, { status, statusText: 'x' });
    return error;
  }

  it('401 ends the session instead of showing a toast', () => {
    expect(fail(401)).toBeInstanceOf(HttpErrorResponse);
    expect(auth.sessionExpired).toHaveBeenCalled();
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('shows the API message and re-throws', () => {
    expect(fail(409, { message: 'Server alpha already exists' })).toBeInstanceOf(HttpErrorResponse);
    expect(notifications.error).toHaveBeenCalledWith('Server alpha already exists');
  });

  it('can be skipped by the caller', () => {
    fail(400, { message: 'x' }, new HttpContext().set(SKIP_ERROR_NOTIFICATION, true));
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('ignores errors of third party APIs', () => {
    fail(500, null, undefined, 'https://api.modrinth.com/v2/search');
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('has a message for every status', () => {
    const e = (status: number, error: unknown = null, headers?: HttpHeaders) =>
      errorMessage(new HttpErrorResponse({ status, error, headers }));
    expect(e(0)).toContain('non raggiungibile');
    expect(e(400)).toBe('Richiesta non valida');
    expect(e(403)).toContain('non consentita');
    expect(e(404, { message: 'Server x not found' })).toBe('Server x not found');
    expect(e(409)).toContain('conflitto');
    expect(e(413)).toContain('troppo grande');
    expect(e(429, null, new HttpHeaders({ 'Retry-After': '30' }))).toContain('30');
    expect(e(500, { errorId: 'abc123' })).toContain('abc123');
    expect(e(503)).toContain('non disponibile');
  });
});
