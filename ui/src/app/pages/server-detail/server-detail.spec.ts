import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { Observable, Subject } from 'rxjs';
import { ServerDetail } from './server-detail';
import { Sse, SseOptions } from '../../core/sse/sse';
import { CONSOLE_MAX_LINES } from '../../app.config';
import { ServerInstanceInfoDto } from '../../core/api-client/model/server-instance-info-dto';
import { testProviders } from '../../testing/providers';

describe('ServerDetail', () => {
  let fixture: ComponentFixture<ServerDetail>;
  let info: Subject<ServerInstanceInfoDto>;
  let logs: Subject<string>;
  let logOptions: SseOptions | undefined;
  let unsubscribed: string[];

  beforeEach(async () => {
    vi.useFakeTimers();
    // jsdom has no layout: the virtual scroll's scrolling is a no-op here
    Element.prototype.scrollTo ??= () => {};
    info = new Subject();
    logs = new Subject();
    unsubscribed = [];
    const sse = {
      stream: (path: string, _parser: unknown, options?: SseOptions) => {
        const source = (path.endsWith('/logs') ? logs : info) as Subject<unknown>;
        if (path.endsWith('/logs')) logOptions = options;
        return new Observable((subscriber) => {
          const s = source.subscribe((v) => subscriber.next(v));
          return () => {
            s.unsubscribe();
            unsubscribed.push(path);
          };
        });
      },
    };
    await TestBed.configureTestingModule({
      imports: [ServerDetail],
      providers: [
        ...testProviders(),
        { provide: Sse, useValue: sse },
        { provide: CONSOLE_MAX_LINES, useValue: 20 },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'alpha' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ServerDetail);
    fixture.detectChanges();
  });

  afterEach(() => vi.useRealTimers());

  it('starts the log stream when the server runs and batches lines', () => {
    info.next({ state: ServerInstanceInfoDto.StateEnum.Running } as ServerInstanceInfoDto);
    for (let i = 0; i < 5; i++) logs.next(`line ${i}`);
    expect(fixture.componentInstance.logs().length).toBe(0); // not yet: batched
    vi.advanceTimersByTime(150);
    expect(fixture.componentInstance.logs().map((l) => l.text)).toEqual([
      'line 0', 'line 1', 'line 2', 'line 3', 'line 4',
    ]);
  });

  it('keeps memory bounded', () => {
    info.next({ state: ServerInstanceInfoDto.StateEnum.Running } as ServerInstanceInfoDto);
    for (let i = 0; i < 500; i++) logs.next(`line ${i}`);
    vi.advanceTimersByTime(150);
    const lines = fixture.componentInstance.logs();
    expect(lines.length).toBeLessThanOrEqual(20);
    expect(lines[lines.length - 1].text).toBe('line 499');
  });

  it('starts over after a reconnection (no duplicated tail)', () => {
    info.next({ state: ServerInstanceInfoDto.StateEnum.Running } as ServerInstanceInfoDto);
    logs.next('a');
    vi.advanceTimersByTime(150);
    logOptions!.onOpen!(true);
    logs.next('b');
    vi.advanceTimersByTime(150);
    expect(fixture.componentInstance.logs().map((l) => l.text)).toEqual(['b']);
  });

  it('closes every stream when destroyed', () => {
    info.next({ state: ServerInstanceInfoDto.StateEnum.Running } as ServerInstanceInfoDto);
    fixture.destroy();
    expect(unsubscribed.sort()).toEqual([
      '/servers/alpha/console/info/stream',
      '/servers/alpha/console/logs',
    ]);
  });

  it('pausing auto-scroll is a toggle', () => {
    const c = fixture.componentInstance;
    c.toggleAutoScroll();
    expect(c.autoScrollPaused()).toBe(true);
    c.toggleAutoScroll();
    expect(c.autoScrollPaused()).toBe(false);
    expect(c.autoScroll()).toBe(true);
  });
  it('splits the logs by level and by text', () => {
    const c = fixture.componentInstance;
    info.next({ state: ServerInstanceInfoDto.StateEnum.Running } as ServerInstanceInfoDto);
    logs.next('[10:00:00] [main/INFO]: hello');
    logs.next('[10:00:01] [main/WARN]: careful');
    logs.next('[10:00:02] [main/ERROR]: boom');
    logs.next('\tat a.b.C.d(C.java:1)');
    vi.advanceTimersByTime(150);

    expect(c.counts()).toMatchObject({ all: 4, info: 1, warn: 1, error: 2 });
    c.setFilter('error');
    expect(c.shown().map((l) => l.body)).toEqual(['[main/ERROR]: boom', '\tat a.b.C.d(C.java:1)']);
    c.setFilter('all');
    c.search.set('CAREFUL');
    expect(c.shown().map((l) => l.body)).toEqual(['[main/WARN]: careful']);
  });

  it('keeps a bounded history of CPU and memory samples and their usage level', () => {
    const c = fixture.componentInstance;
    for (let i = 0; i < 100; i++) {
      info.next({ state: ServerInstanceInfoDto.StateEnum.Running, cpuUsage: i, memoryUsage: 100 + i, maxCpu: 1000, maxMemory: 200 } as ServerInstanceInfoDto);
    }
    expect(c.cpuSamples().length).toBe(60);
    expect(c.cpuSamples().at(-1)).toBe(99);
    expect(c.memoryPercent()).toBeCloseTo(99.5);
    expect(c.level(c.memoryPercent())).toBe('danger');
    expect(c.level(50)).toBe('ok');
    expect(c.level(75)).toBe('warn');
  });

  it('only accepts the server icon as an image data URL', () => {
    const c = fixture.componentInstance;
    info.next({ state: ServerInstanceInfoDto.StateEnum.Running, icon: 'javascript:alert(1)' } as ServerInstanceInfoDto);
    expect(c.icon()).toBeNull();
    info.next({ state: ServerInstanceInfoDto.StateEnum.Running, icon: 'data:image/png;base64,AAAA' } as ServerInstanceInfoDto);
    expect(c.icon()).toBe('data:image/png;base64,AAAA');
  });
});
