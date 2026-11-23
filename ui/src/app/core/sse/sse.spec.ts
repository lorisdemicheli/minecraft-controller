import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { Configuration } from '../api-client';
import { Auth } from '../auth/auth';
import { SSE_FETCH, Sse, SseHttpError, SseState } from './sse';

/** A fetch Response whose body emits the given chunks, then ends (or stays open). */
function response(status: number, chunks: string[] = [], keepOpen = false) {
  const encoder = new TextEncoder();
  let i = 0;
  return {
    ok: status >= 200 && status < 300,
    status,
    body: {
      getReader: () => ({
        read: () =>
          i < chunks.length
            ? Promise.resolve({ done: false, value: encoder.encode(chunks[i++]) })
            : keepOpen
              ? new Promise(() => {})
              : Promise.resolve({ done: true, value: undefined }),
        releaseLock: () => {},
      }),
    },
  } as unknown as Response;
}

describe('Sse', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let auth: { getToken: ReturnType<typeof vi.fn>; sessionExpired: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    fetchMock = vi.fn();
    auth = { getToken: vi.fn(() => 'tok'), sessionExpired: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        { provide: Configuration, useValue: new Configuration({ basePath: 'http://api' }) },
        { provide: SSE_FETCH, useValue: fetchMock },
        { provide: Auth, useValue: auth },
      ],
    });
  });

  const sse = () => TestBed.inject(Sse);

  it('sends the bearer token and parses events', async () => {
    fetchMock.mockResolvedValue(response(200, ['data: 1\n\n', 'data: 2\n\n'], true));
    const got: number[] = [];
    const sub = sse().stream('/x', Number).subscribe((v) => got.push(v));
    await vi.waitFor(() => expect(got).toEqual([1, 2]));
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://api/x');
    expect(init.headers.Authorization).toBe('Bearer tok');
    sub.unsubscribe();
  });

  it('reconnects with backoff after network errors and 5xx, reading the token again', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce(response(503))
      .mockResolvedValue(response(200, ['data: ok\n\n'], true));
    auth.getToken.mockReturnValueOnce('old').mockReturnValue('new');
    const states: SseState[] = [];
    const got: string[] = [];
    const sub = sse()
      .stream('/x', (d) => d, { initialDelayMs: 1, maxDelayMs: 2, onState: (s) => states.push(s) })
      .subscribe((v) => got.push(v));
    await vi.waitFor(() => expect(got).toEqual(['ok']));
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe('Bearer new');
    expect(states).toContain('reconnecting');
    expect(states[states.length - 1]).toBe('open');
    sub.unsubscribe();
  });

  it('reconnects when the server closes the stream and reports reconnections', async () => {
    fetchMock
      .mockResolvedValueOnce(response(200, ['data: a\n\n']))
      .mockResolvedValue(response(200, ['data: b\n\n'], true));
    const opens: boolean[] = [];
    const got: string[] = [];
    const sub = sse()
      .stream('/x', (d) => d, { initialDelayMs: 1, onOpen: (r) => opens.push(r) })
      .subscribe((v) => got.push(v));
    await vi.waitFor(() => expect(got).toEqual(['a', 'b']));
    expect(opens).toEqual([false, true]);
    sub.unsubscribe();
  });

  it('completes on close when reconnectOnClose is false', async () => {
    fetchMock.mockResolvedValue(response(200, ['data: a\n\n']));
    let completed = false;
    sse()
      .stream('/x', (d) => d, { reconnectOnClose: false })
      .subscribe({ complete: () => (completed = true) });
    await vi.waitFor(() => expect(completed).toBe(true));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('ends the session on 401 without retrying', async () => {
    fetchMock.mockResolvedValue(response(401));
    let error: unknown;
    sse().stream('/x', (d) => d, { initialDelayMs: 1 }).subscribe({ error: (e) => (error = e) });
    await vi.waitFor(() => expect(error).toBeInstanceOf(SseHttpError));
    expect(auth.sessionExpired).toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not retry 404', async () => {
    fetchMock.mockResolvedValue(response(404));
    let error: SseHttpError | undefined;
    sse().stream('/x', (d) => d, { initialDelayMs: 1 }).subscribe({ error: (e) => (error = e) });
    await vi.waitFor(() => expect(error?.status).toBe(404));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(auth.sessionExpired).not.toHaveBeenCalled();
  });

  it('gives up after maxRetries', async () => {
    fetchMock.mockResolvedValue(response(500));
    let error: unknown;
    sse()
      .stream('/x', (d) => d, { initialDelayMs: 1, maxDelayMs: 1, maxRetries: 2 })
      .subscribe({ error: (e) => (error = e) });
    await vi.waitFor(() => expect(error).toBeDefined());
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('aborts the request and stops retrying on unsubscribe', async () => {
    fetchMock.mockResolvedValue(response(200, [], true));
    const sub = sse().stream('/x', (d) => d).subscribe();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const signal: AbortSignal = fetchMock.mock.calls[0][1].signal;
    sub.unsubscribe();
    expect(signal.aborted).toBe(true);
  });

  it('cancels a pending reconnection on unsubscribe', async () => {
    fetchMock.mockResolvedValue(response(503));
    const sub = sse().stream('/x', (d) => d, { initialDelayMs: 50, maxDelayMs: 50 }).subscribe();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    sub.unsubscribe();
    await new Promise((r) => setTimeout(r, 120));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('skips events that cannot be parsed', async () => {
    fetchMock.mockResolvedValue(response(200, ['data: {bad\n\n', 'data: {"a":1}\n\n'], true));
    const got: unknown[] = [];
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const sub = sse().stream('/x', (d) => JSON.parse(d)).subscribe((v) => got.push(v));
    await vi.waitFor(() => expect(got).toEqual([{ a: 1 }]));
    spy.mockRestore();
    sub.unsubscribe();
  });
});
