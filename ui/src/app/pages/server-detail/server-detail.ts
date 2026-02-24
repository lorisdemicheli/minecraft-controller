import {
  Component, OnInit, OnDestroy,
  ViewChild, ElementRef,
  inject, signal, PLATFORM_ID
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { CONSOLEService } from '../../core/api-client/api/console.service';
import { ServerInstanceInfoDto } from '../../core/api-client/model/server-instance-info-dto';
import { Sse } from '../../core/sse/sse';

@Component({
  selector: 'app-server-detail',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
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
  private shouldScrollToBottom = true;
  private initializing = true;
  private scrollingToBottom = false;
  private subs = new Subscription();
  private fallbackTimeout: any;

  readonly StateEnum = ServerInstanceInfoDto.StateEnum;

  ngOnInit() {
    if (isPlatformBrowser(this.platformId)) {
      this.startLogStream();
      this.startInfoStream();

      // Fallback: se dopo 3 secondi non sono arrivati abbastanza log, sblocca lo scorrimento
      this.fallbackTimeout = setTimeout(() => {
        if (this.initializing) {
          this.initializing = false;
          this.scrollToBottom();
        }
      }, 3000);
    }
  }

  ngOnDestroy() {
    this.subs.unsubscribe();
    if (this.fallbackTimeout) {
      clearTimeout(this.fallbackTimeout);
    }
  }

  // ─── Scroll ────────────────────────────────────────────────────

  onScroll() {
    const el = this.scrollContainer?.nativeElement;
    
    // Se stiamo inizializzando, forzando lo scroll, o non c'è ancora la barra di scorrimento, blocca
    if (!el || this.initializing || this.scrollingToBottom || el.scrollHeight <= el.clientHeight) return;

    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    this.shouldScrollToBottom = distanceFromBottom < 100;

    if (el.scrollTop <= 30 && !this.isLoadingHistory() && !this.allHistoryLoaded()) {
      this.loadMoreHistory();
    }
  }

  private scrollToBottom() {
    this.scrollingToBottom = true;
    
    // Aspettiamo che Angular aggiorni il DOM
    setTimeout(() => {
      const el = this.scrollContainer?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
      
      // Diamo tempo al browser di generare e farci ignorare l'evento di scroll nativo
      setTimeout(() => {
        this.scrollingToBottom = false;
      }, 50);
    }, 0);
  }

  // ─── Log Storici ───────────────────────────────────────────────

  loadMoreHistory() {
    this.isLoadingHistory.set(true);
    this.shouldScrollToBottom = false;

    const el = this.scrollContainer?.nativeElement;
    const prevScrollHeight = el?.scrollHeight ?? 0;

    this.subs.add(
      this.consoleService.historyLogs(this.serverName, 30, this.historicalLogsLoaded).subscribe({
        next: (logs) => {
          if (logs.length === 0) {
            this.allHistoryLoaded.set(true);
          } else {
            this.logs.set([...logs, ...this.logs()]);
            this.historicalLogsLoaded += logs.length;
            if (logs.length < 30) this.allHistoryLoaded.set(true);

            setTimeout(() => {
              if (el) el.scrollTop = el.scrollHeight - prevScrollHeight;
            }, 0);
          }
          this.isLoadingHistory.set(false);
        },
        error: () => {
          this.isLoadingHistory.set(false);
        }
      })
    );
  }

  // ─── SSE Logs ──────────────────────────────────────────────────

  private startLogStream() {
    this.subs.add(
      this.sse.stream<string>(
        `/servers/${this.serverName}/console/logs`,
        (data) => data
      ).subscribe(line => {
        this.logs.update(prev => [...prev, line]);

        if (this.initializing) {
          if (this.logs().length >= this.LOG_FIRST_LINES) {
            this.initializing = false;
            if (this.fallbackTimeout) clearTimeout(this.fallbackTimeout);
            this.scrollToBottom();
          }
          return;
        }

        if (this.shouldScrollToBottom) this.scrollToBottom();
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

  sendCommand() {
    if (!this.command.trim()) return;
    const cmd = this.command;
    this.command = '';
    this.shouldScrollToBottom = true;
    
    // Forza lo scroll in basso appena l'utente invia il comando
    this.scrollToBottom();
    
    this.consoleService.executeCommand(this.serverName, cmd).subscribe();
  }

  onCommandKeydown(event: KeyboardEvent) {
    if (event.key === 'Enter') this.sendCommand();
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