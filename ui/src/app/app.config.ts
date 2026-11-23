import { ApplicationConfig, InjectionToken, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { provideClientHydration, withEventReplay } from '@angular/platform-browser';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth-interceptor';
import { errorInterceptor } from './core/interceptors/error-interceptor';
import { Configuration } from './core/api-client';
import { environment } from '../environments/environment';
import { apiBasePath } from './core/runtime-config';

/** Maximum number of console lines kept in memory. */
export const CONSOLE_MAX_LINES = new InjectionToken<number>('CONSOLE_MAX_LINES', {
  providedIn: 'root',
  factory: () => environment.consoleMaxLines,
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor, errorInterceptor])),
    provideClientHydration(withEventReplay()),
    {
      provide: Configuration,
      useFactory: () => new Configuration({ basePath: apiBasePath() }),
    },
  ],
};
