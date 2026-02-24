import {
  Component, OnInit, OnDestroy,
  ViewChild, ElementRef,
  inject, signal, PLATFORM_ID,
  afterNextRender,
  effect
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom, Subscription } from 'rxjs';
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

  private readonly LOG_FIRST_LINES = 50;
  private historicalLogsLoaded = this.LOG_FIRST_LINES;
  private canScroll = false;
  private subs = new Subscription();
  private fallbackTimeout: any;

  readonly StateEnum = ServerInstanceInfoDto.StateEnum;

  @ViewChild(CdkVirtualScrollViewport)
  viewport!: CdkVirtualScrollViewport;

private firstBatch = true;
private autoScrollEnabled = false;

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
  
  if (this.canScroll) {
    const distanceFromBottom = total - firstVisible;
    this.autoScrollEnabled = distanceFromBottom <= 25; // 22 visibili + 3 di tolleranza

    if (firstVisible === 0) {
      await this.loadMoreHistory();
      this.viewport.scrollToIndex(this.logs().length - total, 'instant');
    }
  }
}

  ngOnInit() {
    if (isPlatformBrowser(this.platformId)) {
      this.startLogStream();
      this.startInfoStream();
    }
  }

  ngOnDestroy() {
    this.subs.unsubscribe();
    if (this.fallbackTimeout) {
      clearTimeout(this.fallbackTimeout);
    }
  }

  // ─── Scroll ────────────────────────────────────────────────────

  private scrollToBottom() {
    this.viewport.scrollToIndex(this.logs().length, 'smooth');
  }

  // ─── Log Storici ───────────────────────────────────────────────

  async loadMoreHistory() {
  this.isLoadingHistory.set(true);

    const logs = await firstValueFrom(
      this.consoleService.historyLogs(this.serverName, 30, this.historicalLogsLoaded)
    );

    if (logs.length === 0) {
      this.allHistoryLoaded.set(true);
    } else {
      this.logs.set([...logs, ...this.logs()]);
      this.historicalLogsLoaded += logs.length;
      if (logs.length < 30) {
        this.allHistoryLoaded.set(true);
      }
    }
 
    this.isLoadingHistory.set(false);
}

  // ─── SSE Logs ──────────────────────────────────────────────────

  private startLogStream() {
    this.subs.add(
      this.sse.stream<string>(
        `/servers/${this.serverName}/console/logs`,
        (data) => data
      ).subscribe(line => {
        this.logs.update(prev => [...prev, line]);
        if(this.autoScrollEnabled) {
          this.scrollToBottom();
        }
      })
    );
  }

  // ─── SSE Info ──────────────────────────────────────────────────

  private startInfoStream() {
    this.subs.add(
      this.sse.stream<ServerInstanceInfoDto>(
        `/servers/${this.serverName}/console/info/stream`,
        (data) => JSON.parse(data) as ServerInstanceInfoDto
      ).subscribe(info => {
        this.serverInfo.set(info);
      })
    );
  }

  // ─── Comandi ───────────────────────────────────────────────────

  async sendCommand() {
    if (!this.command.trim()) return;
    const cmd = this.command;
    this.command = '';    
    await firstValueFrom(this.consoleService.executeCommand(this.serverName, cmd));
    this.scrollToBottom();
  }

  onCommandKeydown(event: KeyboardEvent) {
    if (event.key === 'Enter') {
      this.sendCommand();
    } 
  }

  // ─── Azioni ────────────────────────────────────────────────────

  start()     { this.consoleService.startServer(this.serverName).subscribe(); }
  stop()      { this.consoleService.stopServer(this.serverName).subscribe(); }
  terminate() { this.consoleService.terminateServer(this.serverName).subscribe(); }

  // ─── Helpers ───────────────────────────────────────────────────

  getStateClass(): string {
    switch (this.serverInfo()?.state) {
      case this.StateEnum.Running:  return 'state--running';
      case this.StateEnum.Starting: return 'state--starting';
      case this.StateEnum.Shutdown: return 'state--shutdown';
      default:                      return 'state--stopped';
    }
  }

  getStateLabel(): string {
    switch (this.serverInfo()?.state) {
      case this.StateEnum.Running:  return 'Online';
      case this.StateEnum.Starting: return 'Avvio...';
      case this.StateEnum.Shutdown: return 'Spegnimento...';
      default:                      return 'Offline';
    }
  }

  getLogClass(line: string): string {
    if (line.includes('WARN'))  return 'log--warn';
    if (line.includes('ERROR')) return 'log--error';
    if (line.includes('INFO'))  return 'log--info';
    return 'log--default';
  }
}