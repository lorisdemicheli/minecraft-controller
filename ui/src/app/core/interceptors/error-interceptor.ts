import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { Auth } from '../auth/auth';
import { Configuration } from '../api-client';
import { Notifications } from '../notifications/notifications';
import { isApiRequest } from './auth-interceptor';
import { SKIP_ERROR_NOTIFICATION } from './http-context';

export { SKIP_ERROR_NOTIFICATION };

/** Message for the user: the API's own `message` when there is one, else a generic text. */
export function errorMessage(error: HttpErrorResponse): string {
  const body = error.error;
  const apiMessage =
    body && typeof body === 'object' && typeof body.message === 'string' ? body.message : null;
  switch (error.status) {
    case 0:
      return 'Backend non raggiungibile: controlla la connessione';
    case 400:
      return apiMessage ?? 'Richiesta non valida';
    case 401:
      return 'Sessione scaduta: accedi di nuovo';
    case 403:
      return 'Operazione non consentita';
    case 404:
      return apiMessage ?? 'Risorsa non trovata';
    case 409:
      return apiMessage ?? 'Operazione in conflitto con lo stato attuale';
    case 413:
      return 'File troppo grande';
    case 429: {
      const retry = error.headers?.get('Retry-After');
      return apiMessage ?? `Troppe richieste${retry ? `: riprova tra ${retry} secondi` : ''}`;
    }
    case 502:
    case 503:
    case 504:
      return apiMessage ?? 'Servizio temporaneamente non disponibile, riprova';
    default:
      if (error.status >= 500) {
        const id = body && typeof body === 'object' && body.errorId ? ` (id ${body.errorId})` : '';
        return (apiMessage ?? 'Errore interno del server') + id;
      }
      return apiMessage ?? `Errore ${error.status}`;
  }
}

/**
 * Central handling of API errors: a 401 ends the session (expired or revoked token), every other
 * error is shown as a notification. The error is re-thrown, so callers can still react to it.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const notifications = inject(Notifications);
  const basePath = inject(Configuration).basePath ?? '';

  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && isApiRequest(req.url, basePath)) {
        if (error.status === 401 && !req.url.endsWith('/login')) {
          auth.sessionExpired();
        } else if (!req.context.get(SKIP_ERROR_NOTIFICATION)) {
          notifications.error(errorMessage(error));
        }
      }
      return throwError(() => error);
    }),
  );
};
