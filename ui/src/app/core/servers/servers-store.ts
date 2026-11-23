import { HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { SERVERService } from '../api-client/api/server.service';
import { ServerInstanceDto } from '../api-client/model/server-instance-dto';
import { SKIP_ERROR_NOTIFICATION, errorMessage } from '../interceptors/error-interceptor';

/**
 * The list of servers, shared by the sidebar and the servers page. Failures are kept in `error`
 * and shown inline (a toast every few seconds would be noise while the cluster is unreachable).
 */
@Injectable({ providedIn: 'root' })
export class ServersStore {
  private api = inject(SERVERService);

  private _servers = signal<ServerInstanceDto[]>([]);
  private _loading = signal(false);
  private _loaded = signal(false);
  private _error = signal<string | null>(null);

  readonly servers = this._servers.asReadonly();
  readonly loading = this._loading.asReadonly();
  /** At least one response (success or failure) has arrived. */
  readonly loaded = this._loaded.asReadonly();
  readonly error = this._error.asReadonly();

  readonly running = computed(
    () => this._servers().filter((s) => s.state === ServerInstanceDto.StateEnum.Running).length,
  );

  refresh() {
    if (this._loading()) return;
    this._loading.set(true);
    const context = new HttpContext().set(SKIP_ERROR_NOTIFICATION, true);
    this.api
      .getAllServers('body', false, { context })
      .pipe(
        finalize(() => {
          this._loading.set(false);
          this._loaded.set(true);
        }),
      )
      .subscribe({
        next: (list) => {
          this._servers.set([...list].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? '')));
          this._error.set(null);
        },
        error: (err: HttpErrorResponse) => this._error.set(errorMessage(err)),
      });
  }
}
