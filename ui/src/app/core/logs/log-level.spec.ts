import { countByFilter, matchesFilter, parseLines } from './log-level';

const lines = (texts: string[]) => texts.map((text, id) => ({ id, text }));

describe('log levels', () => {
  it('reads the level and the timestamp of a line', () => {
    const [info, warn, error] = parseLines(
      lines([
        '[10:02:11] [main/INFO]: Starting',
        '[10:02:19] [Server thread/WARN]: Can\'t keep up!',
        '[10:04:10] [Server thread/ERROR]: Boom',
      ]),
    );
    expect(info).toMatchObject({ level: 'info', time: '[10:02:11]', body: '[main/INFO]: Starting' });
    expect(warn.level).toBe('warn');
    expect(error.level).toBe('error');
  });

  it('keeps a stack trace with the error that produced it', () => {
    const parsed = parseLines(
      lines([
        '[10:04:10] [Server thread/ERROR]: Boom',
        'java.lang.NullPointerException: x',
        '\tat a.b.C.d(C.java:1)',
        '[10:04:11] [Server thread/INFO]: next',
        '\tat orphan',
      ]),
    );
    expect(parsed.map((l) => l.level)).toEqual(['error', 'error', 'error', 'info', 'info']);
  });

  it('recognises the lines about players', () => {
    const parsed = parseLines(
      lines([
        '[10:03:02] [Server thread/INFO]: Steve joined the game',
        '[10:03:40] [Server thread/INFO]: <Steve> hello',
        '[10:05:01] [Server thread/INFO]: Alex lost connection: Disconnected',
        '[10:06:00] [Server thread/INFO]: Saved the game',
      ]),
    );
    expect(parsed.map((l) => l.player)).toEqual([true, true, true, false]);
  });

  it('filters and counts by division', () => {
    const parsed = parseLines(
      lines([
        '[10:00:00] [main/INFO]: a',
        '[10:00:01] [main/WARN]: b',
        '[10:00:02] [main/ERROR]: c',
        '[10:00:03] [main/INFO]: Steve joined the game',
      ]),
    );
    expect(countByFilter(parsed)).toEqual({ all: 4, info: 2, warn: 1, error: 1, players: 1 });
    expect(parsed.filter((l) => matchesFilter(l, 'warn')).map((l) => l.id)).toEqual([1]);
    expect(parsed.filter((l) => matchesFilter(l, 'players')).map((l) => l.id)).toEqual([3]);
    expect(parsed.filter((l) => matchesFilter(l, 'all')).length).toBe(4);
  });
});
