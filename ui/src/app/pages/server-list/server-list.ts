import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpContext } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { finalize } from 'rxjs';
import { INTEGRATIONSService } from '../../core/api-client/api/integrations.service';
import { SERVERService } from '../../core/api-client/api/server.service';
import { ServerInstanceDto } from '../../core/api-client/model/server-instance-dto';
import { SKIP_ERROR_NOTIFICATION } from '../../core/interceptors/error-interceptor';
import { formatCpu, formatMemory } from '../../core/format';
import { Notifications } from '../../core/notifications/notifications';
import { ServersStore } from '../../core/servers/servers-store';
import { BackdropClose } from '../../shared/backdrop-close';
import { CreateServerDialog, SERVER_TYPES } from './create-server-dialog/create-server-dialog';

type State = ServerInstanceDto.StateEnum;
const States = ServerInstanceDto.StateEnum;

const STATE_LABEL: Record<State, string> = {
  RUNNING: 'Online',
  STARTING: 'Avvio…',
  SHUTDOWN: 'Spegnimento…',
  STOPPED: 'Offline',
};

/** Servers overview: state at a glance, search, create (modal) and delete. */
@Component({
  selector: 'app-server-list',
  standalone: true,
  imports: [RouterLink, MatIconModule, CreateServerDialog, BackdropClose],
  templateUrl: './server-list.html',
  styleUrl: './server-list.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServerList implements OnInit {
  private serverApi = inject(SERVERService);
  private integrations = inject(INTEGRATIONSService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private notifications = inject(Notifications);
  private destroyRef = inject(DestroyRef);

  readonly store = inject(ServersStore);

  readonly query = signal('');
  readonly dialogOpen = signal(false);
  readonly curseforgeEnabled = signal(true);
  readonly deleteTarget = signal<ServerInstanceDto | null>(null);
  readonly deleteData = signal(true);
  readonly deleting = signal(false);

  readonly names = computed(() => this.store.servers().map((s) => s.name ?? ''));
  readonly visible = computed(() => {
    const q = this.query().trim().toLowerCase();
    const list = this.store.servers();
    return q ? list.filter((s) => (s.name ?? '').toLowerCase().includes(q)) : list;
  });
  readonly totals = computed(() => {
    const list = this.store.servers();
    return {
      total: list.length,
      online: list.filter((s) => s.state === States.Running).length,
      starting: list.filter((s) => s.state === States.Starting || s.state === States.Shutdown).length,
      cpu: list.reduce((sum, s) => sum + (s.cpu ?? 0), 0),
      memory: list.reduce((sum, s) => sum + (s.memory ?? 0), 0),
    };
  });

  ngOnInit() {
    this.store.refresh();

    const context = new HttpContext().set(SKIP_ERROR_NOTIFICATION, true);
    this.integrations
      .status('body', false, { context })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (s) => this.curseforgeEnabled.set(s.curseforgeEnabled ?? false) });

    // "Nuovo" in the sidebar lands here with ?new=1
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      if (params.has('new')) {
        this.dialogOpen.set(true);
        this.router.navigate([], { queryParams: { new: null }, queryParamsHandling: 'merge', replaceUrl: true });
      }
    });
  }

  onSearch(event: Event) {
    this.query.set((event.target as HTMLInputElement).value);
  }

  onCreated(name: string) {
    this.dialogOpen.set(false);
    this.notifications.info(`Server ${name} creato`);
    this.store.refresh();
  }

  askDelete(server: ServerInstanceDto, event: Event) {
    event.preventDefault();
    event.stopPropagation();
    this.deleteData.set(true);
    this.deleteTarget.set(server);
  }

  cancelDelete() {
    if (!this.deleting()) this.deleteTarget.set(null);
  }

  confirmDelete() {
    const name = this.deleteTarget()?.name;
    if (!name || this.deleting()) return;
    this.deleting.set(true);
    this.serverApi
      .deleteServer(name, this.deleteData(), 'response')
      .pipe(
        finalize(() => this.deleting.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => {
          this.deleteTarget.set(null);
          this.notifications.info(
            response.status === 202
              ? `Server ${name} eliminato: i file andranno nel cestino appena il server si sarà fermato`
              : `Server ${name} eliminato`,
          );
          this.store.refresh();
        },
      });
  }

  typeLabel(type?: ServerInstanceDto.TypeEnum): string {
    return SERVER_TYPES.find((t) => t.value === type)?.label ?? '—';
  }

  typeIcon(type?: ServerInstanceDto.TypeEnum): string {
    return SERVER_TYPES.find((t) => t.value === type)?.icon ?? 'dns';
  }

  stateKey(server: ServerInstanceDto): string {
    return (server.state ?? States.Stopped).toLowerCase();
  }

  stateLabel(server: ServerInstanceDto): string {
    return STATE_LABEL[server.state ?? States.Stopped];
  }

  readonly formatMemory = formatMemory;
  readonly formatCpu = formatCpu;
}
