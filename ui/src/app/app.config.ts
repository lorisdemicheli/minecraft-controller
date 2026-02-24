import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { provideClientHydration, withEventReplay } from '@angular/platform-browser';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth-interceptor';
import { BASE_PATH, Configuration } from './core/api-client';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),   // Angular 21 - gestione errori globali
    provideRouter(routes),
    provideHttpClient(
      withFetch(),                          // necessario per SSR
      withInterceptors([authInterceptor])
    ),
    provideClientHydration(withEventReplay()),
    {
      provide: Configuration,
      useFactory: () => new Configuration({
        basePath: 'http://localhost:8080',
      })
    },
    //  { provide: BASE_PATH, useValue: 'http://localhost:8080' }
  ]
};