import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  PLATFORM_ID,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpContext } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { CdkVirtualScrollViewport, ScrollingModule } from '@angular/cdk/scrolling';
import { Subject, Subscription, bufferTime, filter, finalize } from 'rxjs';
import { CONSOLEService } from '../../core/api-client/api/console.service';
import { FILESYSTEMService } from '../../core/api-client/api/filesystem.service';
import { DiskUsageDto } from '../../core/api-client/model/disk-usage-dto';
import { ServerInstanceInfoDto } from '../../core/api-client/model/server-instance-info-dto';
import { SKIP_ERROR_NOTIFICATION } from '../../core/interceptors/error-interceptor';
import { formatBytes, formatCpu, formatMemory, percent } from '../../core/format';
import { LogBuffer, LogLine } from '../../core/logs/log-buffer';
import { LogFilter, countByFilter, matchesFilter, parseLines } from '../../core/logs/log-level';
import { Sse, SseState } from '../../core/sse/sse';
import { CONSOLE_MAX_LINES } from '../../app.config';
import { BackdropClose } from '../../shared/backdrop-close';
import { Sparkline } from '../../shared/sparkline';

type Tab = 'overview' | 'console';
type StateEnum = ServerInstanceInfoDto.StateEnum;
const States = ServerInstanceInfoDto.StateEnum;

const STATE_LABEL: Record<StateEnum, string> = {
  RUNNING: 'Online',
  STARTING: 'Avvio…',
  SHUTDOWN: 'Spegnimento…',
  STOPPED: 'Offline',
};

const SAMPLES = 60;
const USAGE_REFRESH_MS = 30_000;
const COMMAND_HISTORY = 50;

export const LOG_FILTERS: readonly { key: LogFilter; label: string }[] = [
  { key: 'all', label: 'Tutti' },
  { key: 'info', label: 'Info' },
  { key: 'warn', label: 'Avvisi' },
  { key: 'error', label: 'Errori' },
  { key: 'players', label: 'Giocatori' },
];

export const QUICK_COMMANDS = ['list', 'save-all', 'whitelist list', 'difficulty'] as const;

/**
 * Server page: live info (shared SSE stream), performance, console with bounded log buffer split
 * by level, commands.
 *
 * Logs arrive line by line but the view is refreshed in batches (at most every 100 ms), the buffer
 * keeps at most CONSOLE_MAX_LINES lines, and auto-scroll follows new lines only while the user is
 * at the bottom (or until paused). Every subscription ends with the component (DestroyRef).
 */
@Component({
  selector: 'app-server-detail',
  standalone: true,
  imports: [FormsModule, RouterLink, MatIconModule, ScrollingModule, Sparkline, BackdropClose],
  templateUrl: './server-detail.html',
  styleUrl: './server-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServerDetail implements OnInit {
  private viewport = viewChild(CdkVirtualScrollViewport);

  private route = inject(ActivatedRoute);
  private consoleService = inject(CONSOLEService);
  private fileService = inject(FILESYSTEMService);
  private sse = inject(Sse);
  private destroyRef = inject(DestroyRef);
  private browser = isPlatformBrowser(inject(PLATFORM_ID));

  readonly serverName = this.route.snapshot.paramMap.get('id') ?? '';
  readonly StateEnum = States;
  readonly filters = LOG_FILTERS;
  readonly quickCommands = QUICK_COMMANDS;
  readonly ITEM_SIZE = 22;
  private readonly HISTORY_LOAD_LINES = 50;
  private readonly BOTTOM_TOLERANCE_PX = 40;

  private buffer = new LogBuffer(inject(CONSOLE_MAX_LINES));
  private incoming = new Subject<string>();
  private logSub: Subscription | null = null;
  private commandHistory: string[] = [];
  private historyIndex = -1;

  readonly tab = signal<Tab>('overview');
  readonly serverInfo = signal<ServerInstanceInfoDto | null>(null);
  readonly infoConnection = signal<SseState>('connecting');
  readonly logConnection = signal<SseState | null>(null);

  // performance
  readonly cpuSamples = signal<number[]>([]);
  readonly memorySamples = signal<number[]>([]);
  readonly disk = signal<DiskUsageDto | null>(null);
  readonly diskError = signal(false);
  readonly diskLoading = signal(false);

  // console
  readonly logs = signal<LogLine[]>([]);
  readonly filter = signal<LogFilter>('all');
  readonly search = signal('');
  readonly isLoadingHistory = signal(false);
  readonly allHistoryLoaded = signal(false);
  readonly autoScroll = signal(true);
  /** Auto-scroll paused by the user with the button (scrolling down does not resume it). */
  readonly autoScrollPaused = signal(false);
  readonly command = signal('');

  readonly busy = signal(false);
  readonly confirmTerminate = signal(false);

  readonly state = computed<StateEnum | null>(() => this.serverInfo()?.state ?? null);
  readonly stateKey = computed(() => (this.state() ?? States.Stopped).toLowerCase());
  readonly stateLabel = computed(() => {
    const state = this.state();
    return state ? STATE_LABEL[state] : 'Connessione…';
  });
  readonly isRunning = computed(() => this.state() === States.Running);
  readonly isStopped = computed(() => this.state() === States.Stopped);
  readonly canStart = computed(() => this.isStopped());
  readonly canStop = computed(() => this.state() === States.Running || this.state() === States.Starting);
  readonly live = computed(() => this.infoConnection() === 'open');

  readonly icon = computed(() => {
    const icon = this.serverInfo()?.icon;
    return icon?.startsWith('data:image/') ? icon : null;
  });
  readonly players = computed(() => this.serverInfo()?.population?.players ?? []);

  readonly cpuPercent = computed(() => percent(this.serverInfo()?.cpuUsage, this.serverInfo()?.maxCpu));
  readonly memoryPercent = computed(() => percent(this.serverInfo()?.memoryUsage, this.serverInfo()?.maxMemory));
  readonly playersPercent = computed(() =>
    percent(this.serverInfo()?.population?.online, this.serverInfo()?.population?.max),
  );
  readonly volumePercent = computed(() => {
    const disk = this.disk();
    return disk ? percent((disk.volumeTotalBytes ?? 0) - (disk.volumeFreeBytes ?? 0), disk.volumeTotalBytes) : null;
  });
  readonly diskSharePercent = computed(() => percent(this.disk()?.usedBytes, this.disk()?.volumeTotalBytes));

  private parsed = computed(() => parseLines(this.logs()));
  readonly counts = computed(() => countByFilter(this.parsed()));
  readonly shown = computed(() => {
    const filter = this.filter();
    const query = this.search().trim().toLowerCase();
    return this.parsed().filter(
      (line) => matchesFilter(line, filter) && (!query || line.text.toLowerCase().includes(query)),
    );
  });
  private filtering = computed(() => this.filter() !== 'all' || this.search().trim() !== '');

  readonly formatCpu = formatCpu;
  readonly formatMemory = formatMemory;
  readonly formatBytes = formatBytes;

  ngOnInit() {
    if (!this.browser) return;

    // one view refresh per batch of lines, not per line
    this.incoming
      .pipe(
        bufferTime(100),
        filter((lines) => lines.length > 0),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((lines) => {
        this.buffer.append(lines);
        this.refresh();
        if (this.autoScroll() && !this.autoScrollPaused()) this.scrollToBottom();
      });

    this.sse
      .stream<ServerInstanceInfoDto>(
        `/servers/${encodeURIComponent(this.serverName)}/console/info/stream`,
        (data) => JSON.parse(data) as ServerInstanceInfoDto,
        { onState: (s) => this.infoConnection.set(s), reconnectOnClose: false },
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (info) => {
          this.serverInfo.set(info);
          this.sample(info);
          if (info.state === States.Running && !this.logSub) this.startLogStream();
        },
        error: () => this.infoConnection.set('closed'),
      });

    this.destroyRef.onDestroy(() => this.stopLogStream(false));

    this.loadUsage();
    const timer = setInterval(() => this.loadUsage(), USAGE_REFRESH_MS);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  selectTab(tab: Tab) {
    this.tab.set(tab);
    if (tab === 'console') this.scrollToBottom();
  }

  // ─── Prestazioni ───────────────────────────────────────────────

  private sample(info: ServerInstanceInfoDto) {
    if (info.cpuUsage != null) this.cpuSamples.update((s) => [...s, info.cpuUsage as number].slice(-SAMPLES));
    if (info.memoryUsage != null) this.memorySamples.update((s) => [...s, info.memoryUsage as number].slice(-SAMPLES));
  }

  loadUsage() {
    if (this.diskLoading()) return;
    this.diskLoading.set(true);
    const context = new HttpContext().set(SKIP_ERROR_NOTIFICATION, true);
    this.fileService
      .usage(this.serverName, 'body', false, { context })
      .pipe(
        finalize(() => this.diskLoading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (usage) => {
          this.disk.set(usage);
          this.diskError.set(false);
        },
        error: () => this.diskError.set(true),
      });
  }

  /** Colour of a usage bar: it warms up as the limit gets close. */
  level(value: number | null): 'ok' | 'warn' | 'danger' {
    if (value == null || value < 70) return 'ok';
    return value < 90 ? 'warn' : 'danger';
  }

  // ─── Scroll ────────────────────────────────────────────────────

  onScroll() {
    const viewport = this.viewport();
    if (!viewport) return;
    const atBottom = viewport.measureScrollOffset('bottom') <= this.BOTTOM_TOLERANCE_PX;
    this.autoScroll.set(atBottom);
    if (viewport.measureScrollOffset('top') === 0) this.loadMoreHistory();
  }

  toggleAutoScroll() {
    const paused = !this.autoScrollPaused();
    this.autoScrollPaused.set(paused);
    if (!paused) {
      this.autoScroll.set(true);
      this.scrollToBottom();
    }
  }

  private scrollToBottom() {
    if (!this.browser) return;
    requestAnimationFrame(() => this.viewport()?.scrollToIndex(this.shown().length, 'auto'));
  }

  // ─── Filtri ────────────────────────────────────────────────────

  setFilter(filter: LogFilter) {
    this.filter.set(filter);
    this.scrollToBottom();
  }

  onSearch(event: Event) {
    this.search.set((event.target as HTMLInputElement).value);
    this.scrollToBottom();
  }

  downloadLogs() {
    const text = this.shown()
      .map((line) => line.text)
      .join('\n');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${this.serverName}-${this.filter()}.log`;
    link.click();
    URL.revokeObjectURL(url);
  }

  // ─── Log storici ───────────────────────────────────────────────

  loadMoreHistory() {
    if (this.isLoadingHistory() || this.allHistoryLoaded() || !this.logSub) return;
    if (!this.buffer.historyAvailable) {
      this.allHistoryLoaded.set(true); // memory limit reached
      return;
    }
    this.isLoadingHistory.set(true);
    const before = this.buffer.size;
    this.consoleService
      .historyLogs(this.serverName, this.HISTORY_LOAD_LINES, this.buffer.fromEnd)
      .pipe(
        finalize(() => this.isLoadingHistory.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (lines) => {
          const added = this.buffer.prepend(lines);
          if (lines.length < this.HISTORY_LOAD_LINES || added < lines.length) {
            this.allHistoryLoaded.set(true);
          }
          this.refresh();
          // keep the line the user was looking at in place (only meaningful when nothing is filtered out)
          if (!this.filtering()) {
            requestAnimationFrame(() => this.viewport()?.scrollToIndex(this.buffer.size - before, 'auto'));
          }
        },
      });
  }

  // ─── Log live ──────────────────────────────────────────────────

  private startLogStream() {
    this.logSub = this.sse
      .stream<string>(`/servers/${encodeURIComponent(this.serverName)}/console/logs`, (data) => data, {
        onState: (s) => this.logConnection.set(s),
        // after a reconnection the server sends its tail again: start over to avoid duplicates
        onOpen: (reconnect) => {
          if (reconnect) this.resetLogs();
        },
      })
      .subscribe({
        next: (line) => this.incoming.next(line),
        error: () => this.logConnection.set('closed'),
      });
  }

  private stopLogStream(clear = true) {
    this.logSub?.unsubscribe();
    this.logSub = null;
    this.logConnection.set(null);
    if (clear) this.resetLogs();
  }

  private resetLogs() {
    this.buffer.clear();
    this.allHistoryLoaded.set(false);
    this.autoScroll.set(true);
    this.refresh();
  }

  private refresh() {
    this.logs.set(this.buffer.snapshot());
  }

  trackLine(_: number, line: { id: number }) {
    return line.id;
  }

  // ─── Comandi ───────────────────────────────────────────────────

  sendCommand(text = this.command()) {
    const cmd = text.trim();
    if (!cmd || this.busy()) return;
    this.busy.set(true);
    this.consoleService
      .executeCommand(this.serverName, cmd)
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.commandHistory = [cmd, ...this.commandHistory.filter((c) => c !== cmd)].slice(0, COMMAND_HISTORY);
          this.historyIndex = -1;
          this.command.set('');
          this.autoScroll.set(true);
          this.scrollToBottom();
        },
        // errors are shown by the HTTP error interceptor; the command stays in the input
      });
  }

  onCommandKeydown(event: KeyboardEvent) {
    // Enter submits the form; the arrows walk through the commands already sent
    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      const next = this.historyIndex + (event.key === 'ArrowUp' ? 1 : -1);
      if (next < -1 || next >= this.commandHistory.length) return;
      event.preventDefault();
      this.historyIndex = next;
      this.command.set(next === -1 ? '' : this.commandHistory[next]);
    }
  }

  // ─── Azioni ────────────────────────────────────────────────────

  start() {
    this.action(this.consoleService.startServer(this.serverName), () =>
      this.serverInfo.set({ ...this.serverInfo(), state: States.Starting } as ServerInstanceInfoDto),
    );
  }

  stop() {
    this.action(this.consoleService.stopServer(this.serverName), () => this.stopped());
  }

  terminate() {
    this.confirmTerminate.set(false);
    this.action(this.consoleService.terminateServer(this.serverName), () => this.stopped());
  }

  private stopped() {
    this.serverInfo.set({ ...this.serverInfo(), state: States.Shutdown } as ServerInstanceInfoDto);
    this.stopLogStream();
  }

  private action(request: ReturnType<CONSOLEService['startServer']>, done: () => void) {
    if (this.busy()) return;
    this.busy.set(true);
    request
      .pipe(
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({ next: done });
  }
}
