import { environment } from '../../environments/environment';

/**
 * Settings that the container injects when it starts (`GET /config.js`, from `API_BASE_URL`), so
 * the same image works on any domain. Without them the build-time `environment` is used.
 */
export interface RuntimeConfig {
  apiBasePath?: string | null;
}

declare global {
  interface Window {
    __APP_CONFIG__?: RuntimeConfig;
  }
}

/** Base URL of the API: injected at runtime when present, otherwise the build-time value. */
export function apiBasePath(runtime: RuntimeConfig | undefined = globalThis.window?.__APP_CONFIG__): string {
  return runtime?.apiBasePath || environment.apiBasePath;
}
