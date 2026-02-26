import {
  Component, OnInit, OnDestroy,
  ViewChild, ElementRef,
  inject, signal, PLATFORM_ID,
  effect,
  ChangeDetectionStrategy
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom, fromEvent, Subscription } from 'rxjs';
import { CONSOLEService } from '../../core/api-client/api/console.service';
import { ServerInstanceInfoDto } from '../../core/api-client/model/server-instance-info-dto';
import { Sse } from '../../core/sse/sse';
import { CdkVirtualScrollViewport, ScrollingModule } from '@angular/cdk/scrolling';

@Component({
  selector: 'app-server-detail',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    ScrollingModule
  ],
  templateUrl: './server-detail.html',
  styleUrl: './server-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServerDetail implements OnInit, OnDestroy {
  @ViewChild('scrollContainer') scrollContainer!: ElementRef<HTMLDivElement>;

  private route = inject(ActivatedRoute);
  private consoleService = inject(CONSOLEService);
  private sse = inject(Sse);
  private platformId = inject(PLATFORM_ID);

  serverName = this.route.snapshot.paramMap.get('id') ?? '';

  serverInfo = signal<ServerInstanceInfoDto | null>(null);
  logs = signal<string[]>([]);
  isLoadingHistory = signal(false);
  allHistoryLoaded = signal(false);
  command = '';

  private readonly HISTORY_LOAD_LINES = 50;
  private historicalLogsLoaded = 0;
  private canScroll = false;
  private subs = new Subscription();
  private logSub = new Subscription();
  private firstBatch = true;
  private autoScrollEnabled = false;

  readonly StateEnum = ServerInstanceInfoDto.StateEnum;

  @ViewChild(CdkVirtualScrollViewport)
  viewport!: CdkVirtualScrollViewport;


  constructor() {
    effect(() => {
      const logs = this.logs();

      if (logs.length > 0 && this.firstBatch) {
        this.firstBatch = false;
        setTimeout(() => this.scrollToBottom(), 50);
        this.canScroll = true;
        this.autoScrollEnabled = true;
      }
    });
  }

  async onScrolledIndexChange(firstVisible: number) {
    const total = this.viewport.getDataLength();

    if (this.canScroll || this.autoScrollEnabled) {
      const distanceFromBottom = total - firstVisible;
      this.autoScrollEnabled = distanceFromBottom <= 25; // 22 visibili + 3 di tolleranza

      if (firstVisible === 0 && !this.isLoadingHistory() && !this.allHistoryLoaded()) {
        await this.loadMoreHistory();
        this.viewport.scrollToIndex(this.logs().length - total, 'instant');
      }
    }
  }

  ngOnInit() {
    if (isPlatformBrowser(this.platformId)) {
      this.startInfoStream();
    
      this.subs.add(
        fromEvent(window, 'beforeunload').subscribe(() => {
          this.ngOnDestroy();
        })
      );
    }
  }

  ngOnDestroy() {
    this.subs.unsubscribe();
    this.logSub.unsubscribe();
  }

  // ─── Scroll ────────────────────────────────────────────────────

  private async scrollToBottom() {
    setTimeout(() => this.viewport.scrollToIndex(this.logs().length, 'smooth'), 50);
  }

  // ─── Log Storici ───────────────────────────────────────────────

  async loadMoreHistory() {
    this.isLoadingHistory.set(true);

    const logs = await firstValueFrom(
      this.consoleService.historyLogs(this.serverName, this.HISTORY_LOAD_LINES, this.historicalLogsLoaded)
    );

    if (logs.length === 0) {
      this.allHistoryLoaded.set(true);
    } else {
      this.logs.set([...logs, ...this.logs()]);
      this.historicalLogsLoaded += logs.length;
      if (logs.length < this.HISTORY_LOAD_LINES) {
        this.allHistoryLoaded.set(true);
      }
    }

    this.isLoadingHistory.set(false);
  }

  // ─── SSE Logs ──────────────────────────────────────────────────

  private startLogStream() {
    this.logSub = this.sse.stream<string>(
      `/servers/${this.serverName}/console/logs`,
      (data) => data
    ).subscribe(line => {
      this.logs.update(prev => [...prev, line]);
      if (this.autoScrollEnabled) {
        this.scrollToBottom();
      }
    });
  }

  private stopLogStream() {
    this.logSub.unsubscribe();
    this.logSub = new Subscription();
    this.logs.set([]);
    this.historicalLogsLoaded = 0;
    this.allHistoryLoaded.set(false);
    this.firstBatch = true;
    this.autoScrollEnabled = false;
    this.canScroll = false;
  }

  // ─── SSE Info ──────────────────────────────────────────────────

  private startInfoStream() {
    this.subs.add(
      this.sse.stream<ServerInstanceInfoDto>(
        `/servers/${this.serverName}/console/info/stream`,
        (data) => JSON.parse(data) as ServerInstanceInfoDto
      ).subscribe(info => {
        this.serverInfo.set(info);
        if (info.state === this.StateEnum.Running && this.logs().length === 0) {
          this.startLogStream();
        }
      })
    );
  }

  // ─── Comandi ───────────────────────────────────────────────────

  async sendCommand() {
    if (!this.command.trim()) return;
    const cmd = this.command;
    this.command = '';
    this.canScroll = false;
    await firstValueFrom(this.consoleService.executeCommand(this.serverName, cmd));
    this.scrollToBottom();
    this.canScroll = true;
  }

  onCommandKeydown(event: KeyboardEvent) {
    if (event.key === 'Enter') {
      this.sendCommand();
    }
  }

  // ─── Azioni ────────────────────────────────────────────────────

  async start() {
    await firstValueFrom(this.consoleService.startServer(this.serverName));
    this.serverInfo.set({ state: this.StateEnum.Starting } as ServerInstanceInfoDto);
  }

  async stop() {
    await firstValueFrom(this.consoleService.stopServer(this.serverName));
    this.serverInfo.set({ ...this.serverInfo(), state: this.StateEnum.Shutdown } as ServerInstanceInfoDto);
    this.stopLogStream();
  }

  async terminate() {
    await firstValueFrom(this.consoleService.terminateServer(this.serverName));
    this.serverInfo.set({ ...this.serverInfo(), state: this.StateEnum.Shutdown } as ServerInstanceInfoDto);
    this.stopLogStream();
  }

  // ─── Helpers ───────────────────────────────────────────────────

  getStateClass(): string {
    switch (this.serverInfo()?.state) {
      case this.StateEnum.Running: return 'state--running';
      case this.StateEnum.Starting: return 'state--starting';
      case this.StateEnum.Shutdown: return 'state--shutdown';
      default: return 'state--stopped';
    }
  }

  getStateLabel(): string {
    switch (this.serverInfo()?.state) {
      case this.StateEnum.Running: return 'Online';
      case this.StateEnum.Starting: return 'Avvio...';
      case this.StateEnum.Shutdown: return 'Spegnimento...';
      default: return 'Offline';
    }
  }

  getLogClass(line: string): string {
    const match = line.match(/\[[\w\s]+\/([A-Z]+)\]/);
  switch (match?.[1]) {
      case 'WARN': return 'log--warn';
      case 'ERROR': return 'log--error';
      case 'INFO': return 'log--info';
      default: return 'log--default';
    }
  }
}