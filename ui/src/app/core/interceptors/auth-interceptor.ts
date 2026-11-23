import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Auth } from '../auth/auth';
import { Configuration } from '../api-client';

/** Adds the JWT, but only to requests for our API (never to Modrinth or other third parties). */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(Auth).getToken();
  const basePath = inject(Configuration).basePath ?? '';

  if (!token || !isApiRequest(req.url, basePath)) return next(req);

  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};

export function isApiRequest(url: string, basePath: string): boolean {
  if (!basePath) return url.startsWith('/') && !url.startsWith('//');
  const base = basePath.endsWith('/') ? basePath : basePath + '/';
  return url === basePath || url.startsWith(base);
}
