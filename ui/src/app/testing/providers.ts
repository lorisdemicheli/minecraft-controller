import { EnvironmentProviders, Provider } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Configuration } from '../core/api-client';

/** What every component under test needs: router, mocked HTTP and the API configuration. */
export function testProviders(): (Provider | EnvironmentProviders)[] {
  return [
    provideRouter([]),
    provideHttpClient(),
    provideHttpClientTesting(),
    { provide: Configuration, useValue: new Configuration({ basePath: 'http://api' }) },
  ];
}
