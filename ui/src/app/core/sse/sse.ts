import { Injectable, inject, PLATFORM_ID, NgZone } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Observable } from 'rxjs';
import { Auth } from '../auth/auth';
import { Configuration } from '../api-client';

@Injectable({ providedIn: 'root' })
export class Sse {
  private configuration = inject(Configuration);
  private auth = inject(Auth);
  private zone = inject(NgZone);
  private platformId = inject(PLATFORM_ID);

  stream<T>(path: string, parser: (data: string) => T): Observable<T> {
    return new Observable<T>(observer => {
      if (!isPlatformBrowser(this.platformId)) {
        observer.complete();
        return;
      }

      const abort = new AbortController();
      const token = this.auth.getToken();
      const url = `${this.configuration.basePath}${path}`;

      (async () => {
        try {
          const response = await fetch(url, {
            headers: {
              'Authorization': `Bearer ${token ?? ''}`,
              'Accept': 'text/event-stream',
              'Cache-Control': 'no-cache'
            },
            signal: abort.signal
          });

          if (!response.ok || !response.body) {
            throw new Error(`HTTP ${response.status}`);
          }

          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          let buffer = '';

          while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            // value è solo il chunk nuovo — nessun accumulo come partialText
            buffer += decoder.decode(value, { stream: true });
            const parts = buffer.split('\n\n');
            buffer = parts.pop() ?? '';

            for (const block of parts) {
              for (const line of block.split('\n')) {
                if (line.startsWith('data:')) {
                  const data = line.slice(5).trim();
                  if (data) {
                    try {
                      const parsed = parser(data);
                      this.zone.run(() => observer.next(parsed));
                    } catch (e) {
                      console.error('SSE parse error:', e, 'data:', data);
                    }
                  }
                }
              }
            }
          }

          this.zone.run(() => observer.complete());
        } catch (err: any) {
          if (err?.name !== 'AbortError') {
            console.error('SSE errore:', err);
            this.zone.run(() => observer.error(err));
          }
        }
      })();

      return () => abort.abort();
    });
  }
}