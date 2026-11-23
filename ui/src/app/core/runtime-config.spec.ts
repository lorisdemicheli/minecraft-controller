import { environment } from '../../environments/environment';
import { apiBasePath } from './runtime-config';

describe('apiBasePath', () => {
  it('prefers the value injected when the container starts', () => {
    expect(apiBasePath({ apiBasePath: 'https://api.example.com' })).toBe('https://api.example.com');
  });

  it('falls back to the build-time value', () => {
    expect(apiBasePath(undefined)).toBe(environment.apiBasePath);
    expect(apiBasePath({ apiBasePath: null })).toBe(environment.apiBasePath);
    expect(apiBasePath({ apiBasePath: '' })).toBe(environment.apiBasePath);
  });
});
