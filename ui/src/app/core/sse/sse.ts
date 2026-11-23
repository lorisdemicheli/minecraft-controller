import { Injectable, InjectionToken, NgZone, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Observable } from 'rxjs';
import { Auth } from '../auth/auth';
import { Configuration } from '../api-client';
import { SseParser } from './sse-parser';

/** `fetch` used by the SSE client (replaced in tests). */
export const SSE_FETCH = new InjectionToken<typeof fetch>('SSE_FETCH', {
  providedIn: 'root',
  factory: () => (input, init) => fetch(input, init),
});

export type SseState = 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface SseOptions {
  /** Reconnect when the server closes the stream normally (default true). */
  reconnectOnClose?: boolean;
  /** Attempts before giving up; default: never give up. */
  maxRetries?: number;
  initialDelayMs?: number;
  maxDelayMs?: number;
  /** Connection state changes, e.g. to show "reconnecting..." in the page. */
  onState?: (state: SseState, attempt: number) => void;
  /** Called on every (re)connection before its first event, e.g. to reset a log buffer. */
  onOpen?: (reconnect: boolean) => void;
}

/** HTTP error of an SSE request. 4xx (except 408/429) are final, everything else is retried. */
export class SseHttpError extends Error {
  constructor(readonly status: number) {
    super(`HTTP ${status}`);
  }

  get retryable(): boolean {
    return this.status >= 500 || this.status === 408 || this.status === 429;
  }
}

/**
 * Server-Sent Events over `fetch` (EventSource cannot send the Authorization header).
 * The observable keeps the connection alive: on network errors, 5xx and closed streams it
 * reconnects with exponential backoff and jitter, immediately when the browser comes back online.
 * A 401 ends the session; other 4xx end the stream with an error. Unsubscribing aborts the request.
 */
@Injectable({ providedIn: 'root' })
export class Sse {
  private configuration = inject(Configuration);
  private auth = inject(Auth);
  private zone = inject(NgZone);
  private platformId = inject(PLATFORM_ID);
  private fetchFn = inject(SSE_FETCH);

  stream<T>(path: string, parser: (data: string) => T, options: SseOptions = {}): Observable<T> {
    const reconnectOnClose = options.reconnectOnClose ?? true;
    const maxRetries = options.maxRetries ?? Number.POSITIVE_INFINITY;
    const initialDelay = options.initialDelayMs ?? 1000;
    const maxDelay = options.maxDelayMs ?? 30000;

    return new Observable<T>((observer) => {
      if (!isPlatformBrowser(this.platformId)) {
        observer.complete();
        return;
      }

      const abort = new AbortController();
      let stopped = false;
      let attempt = 0;
      let opened = false;
      let lastEventId: string | undefined;
      let serverRetry: number | undefined;
      let wake: (() => void) | null = null;

      const state = (s: SseState) => options.onState?.(s, attempt);
      const onOnline = () => wake?.();
      window.addEventListener('online', onOnline);

      const wait = (ms: number) =>
        new Promise<void>((resolve) => {
          const timer = setTimeout(done, ms);
          function done() {
            clearTimeout(timer);
            wake = null;
            resolve();
          }
          wake = done;
          abort.signal.addEventListener('abort', done, { once: true });
        });

      const backoff = () => {
        const base = serverRetry ?? initialDelay;
        const step = Math.min(maxDelay, base * 2 ** Math.min(attempt - 1, 10));
        return step / 2 + Math.random() * (step / 2); // equal jitter
      };

      const connectOnce = async () => {
        const headers: Record<string, string> = {
          Accept: 'text/event-stream',
          'Cache-Control': 'no-cache',
        };
        const token = this.auth.getToken(); // re-read: it may have changed
        if (token) headers['Authorization'] = `Bearer ${token}`;
        if (lastEventId) headers['Last-Event-ID'] = lastEventId;

        const response = await this.fetchFn(`${this.configuration.basePath}${path}`, {
          headers,
          signal: abort.signal,
        });
        if (!response.ok || !response.body) {
          throw new SseHttpError(response.status);
        }

        attempt = 0;
        state('open');
        options.onOpen?.(opened);
        opened = true;

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        const sse = new SseParser();
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            for (const message of sse.push(decoder.decode(value, { stream: true }))) {
              lastEventId = message.id ?? lastEventId;
              serverRetry = message.retry ?? serverRetry;
              let parsed: T;
              try {
                parsed = parser(message.data);
              } catch (e) {
                console.error('SSE: cannot parse event', e);
                continue;
              }
              this.zone.run(() => observer.next(parsed));
            }
          }
        } finally {
          reader.releaseLock();
        }
      };

      (async () => {
        state('connecting');
        while (!stopped) {
          try {
            await connectOnce();
            if (stopped) return;
            if (!reconnectOnClose) {
              state('closed');
              this.zone.run(() => observer.complete());
              return;
            }
          } catch (err: unknown) {
            if (stopped || (err as Error)?.name === 'AbortError') return;
            if (err instanceof SseHttpError && !err.retryable) {
              state('closed');
              if (err.status === 401) {
                this.zone.run(() => this.auth.sessionExpired());
              }
              this.zone.run(() => observer.error(err));
              return;
            }
          }
          attempt++;
          if (attempt > maxRetries) {
            state('closed');
            this.zone.run(() => observer.error(new Error('SSE: too many reconnection attempts')));
            return;
          }
          state('reconnecting');
          await wait(backoff());
        }
      })();

      return () => {
        stopped = true;
        window.removeEventListener('online', onOnline);
        abort.abort();
      };
    });
  }
}
