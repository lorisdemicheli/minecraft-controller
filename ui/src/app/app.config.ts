import { ApplicationConfig, InjectionToken, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { provideClientHydration, withEventReplay } from '@angular/platform-browser';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth-interceptor';
import { BASE_PATH, Configuration } from './core/api-client';
import { environment } from '../environments/environment';

export const CURSEFORGE_API_KEY = new InjectionToken<string>('CURSEFORGE_API_KEY');

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(
      withFetch(),
      withInterceptors([authInterceptor])
    ),
    provideClientHydration(withEventReplay()),
    {
      provide: Configuration,
      useFactory: () => new Configuration({
        basePath: environment.apiBasePath,
      })
    },
    {
      provide: CURSEFORGE_API_KEY,
      useValue: environment.curseforgeApiKey
    }
  ]
};