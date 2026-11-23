import { LogLine } from './log-buffer';

export type LogLevel = 'info' | 'warn' | 'error' | 'debug';
/** The divisions of the console: everything, one per level, or the lines about players. */
export type LogFilter = 'all' | 'info' | 'warn' | 'error' | 'players';

export interface ParsedLine extends LogLine {
  level: LogLevel;
  /** `[12:34:56]` when the line starts with one. */
  time: string;
  /** The line without its timestamp. */
  body: string;
  player: boolean;
}

const LEVEL_TAG = /\/(INFO|WARN|ERROR|FATAL|DEBUG|TRACE)\]/;
const TIME = /^(\[\d{1,2}:\d{2}:\d{2}(?:\.\d+)?\])\s?/;
const PLAYER_EVENT =
  /(joined the game|left the game|lost connection|logged in with entity|UUID of player|issued server command|^\[[^\]]*\]:?\s*<[^<>\s]+>\s|\]: <[^<>\s]+>\s)/;

function levelOf(tag: string): LogLevel {
  switch (tag) {
    case 'WARN':
      return 'warn';
    case 'ERROR':
    case 'FATAL':
      return 'error';
    case 'DEBUG':
    case 'TRACE':
      return 'debug';
    default:
      return 'info';
  }
}

/**
 * Classifies the console lines. A line without a level tag that does not start a new entry (a stack
 * trace, a wrapped message) belongs to the entry before it, so an error stays together with its trace.
 */
export function parseLines(lines: readonly LogLine[]): ParsedLine[] {
  let previous: LogLevel = 'info';
  return lines.map((line) => {
    const tag = LEVEL_TAG.exec(line.text)?.[1];
    const continuation = !tag && !line.text.startsWith('[');
    const level = tag ? levelOf(tag) : continuation ? previous : 'info';
    previous = level;

    const timeMatch = TIME.exec(line.text);
    return {
      ...line,
      level,
      time: timeMatch?.[1] ?? '',
      body: timeMatch ? line.text.slice(timeMatch[0].length) : line.text,
      player: PLAYER_EVENT.test(line.text),
    };
  });
}

export function matchesFilter(line: ParsedLine, filter: LogFilter): boolean {
  return filter === 'all' || (filter === 'players' ? line.player : line.level === filter);
}

export function countByFilter(lines: readonly ParsedLine[]): Record<LogFilter, number> {
  const counts: Record<LogFilter, number> = { all: lines.length, info: 0, warn: 0, error: 0, players: 0 };
  for (const line of lines) {
    if (line.level === 'info') counts.info++;
    else if (line.level === 'warn') counts.warn++;
    else if (line.level === 'error') counts.error++;
    if (line.player) counts.players++;
  }
  return counts;
}
