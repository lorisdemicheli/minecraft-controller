import { LogBuffer } from './log-buffer';

describe('LogBuffer', () => {
  it('keeps at most `capacity` lines, dropping the oldest in blocks', () => {
    const b = new LogBuffer(100);
    for (let i = 0; i < 1000; i++) b.append([`line ${i}`]);
    expect(b.size).toBeLessThanOrEqual(100);
    const lines = b.snapshot();
    expect(lines[lines.length - 1].text).toBe('line 999');
    expect(b.historyAvailable).toBe(false);
  });

  it('gives every line a unique stable id', () => {
    const b = new LogBuffer(10);
    b.append(['a', 'b']);
    b.prepend(['z']);
    const ids = b.snapshot().map((l) => l.id);
    expect(new Set(ids).size).toBe(3);
    expect(b.snapshot().map((l) => l.text)).toEqual(['z', 'a', 'b']);
  });

  it('tracks the history offset across live and old lines', () => {
    const b = new LogBuffer(100);
    b.append(['t1', 't2']); // tail
    b.append(['live']);
    expect(b.fromEnd).toBe(3);
    b.prepend(['h1', 'h2']);
    expect(b.fromEnd).toBe(5);
  });

  it('prepends only what fits', () => {
    const b = new LogBuffer(3);
    b.append(['x', 'y']);
    expect(b.prepend(['a', 'b', 'c'])).toBe(1);
    expect(b.snapshot().map((l) => l.text)).toEqual(['c', 'x', 'y']);
    expect(b.historyAvailable).toBe(false);
  });

  it('cuts very long lines', () => {
    const b = new LogBuffer(10, 20);
    b.append(['x'.repeat(10_000)]);
    expect(b.snapshot()[0].text.length).toBeLessThan(40);
  });

  it('snapshot is a copy (the virtual scroll sees a new array)', () => {
    const b = new LogBuffer(10);
    b.append(['a']);
    const first = b.snapshot();
    b.append(['b']);
    expect(first.length).toBe(1);
    expect(b.snapshot()).not.toBe(first);
  });

  it('clear resets everything', () => {
    const b = new LogBuffer(2);
    b.append(['a', 'b', 'c']);
    b.clear();
    expect(b.size).toBe(0);
    expect(b.fromEnd).toBe(0);
    expect(b.historyAvailable).toBe(true);
  });
});
