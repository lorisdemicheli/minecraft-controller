import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { authInterceptor, isApiRequest } from './auth-interceptor';
import { Auth } from '../auth/auth';
import { Configuration } from '../api-client';

describe('authInterceptor', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Auth, useValue: { getToken: () => 'tok' } },
        { provide: Configuration, useValue: new Configuration({ basePath: 'http://api.test' }) },
      ],
    });
  });

  it('adds the token to API requests', () => {
    TestBed.inject(HttpClient).get('http://api.test/servers').subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne('http://api.test/servers');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok');
  });

  it('never sends the token to third parties', () => {
    const http = TestBed.inject(HttpClient);
    http.get('https://api.modrinth.com/v2/search').subscribe();
    http.get('http://api.test.evil.com/x').subscribe();
    const ctrl = TestBed.inject(HttpTestingController);
    expect(ctrl.expectOne('https://api.modrinth.com/v2/search').request.headers.has('Authorization')).toBe(false);
    expect(ctrl.expectOne('http://api.test.evil.com/x').request.headers.has('Authorization')).toBe(false);
  });

  it('isApiRequest', () => {
    expect(isApiRequest('http://a/x', 'http://a')).toBe(true);
    expect(isApiRequest('http://ab/x', 'http://a')).toBe(false);
    expect(isApiRequest('/x', '')).toBe(true);
    expect(isApiRequest('//evil/x', '')).toBe(false);
  });
});
