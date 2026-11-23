/** A console line with a stable id (for trackBy in the virtual scroll). */
export interface LogLine {
  id: number;
  text: string;
}

/**
 * Bounded buffer of console lines.
 *
 * - Live lines are appended in O(1) amortized; when the capacity is exceeded the oldest lines are
 *   dropped in blocks (not one by one), so trimming is also amortized O(1).
 * - Older history can be prepended while there is room; once live lines had to evict history,
 *   older history cannot be loaded any more (`historyAvailable` turns false).
 * - `fromEnd` counts the lines of the file that are already in the buffer, i.e. the `skip` to ask
 *   the next page of history with.
 * - `snapshot()` returns a new array (needed by the virtual scroll to see the change): callers
 *   should take it at most once per frame/batch, never per line.
 */
export class LogBuffer {
  private lines: LogLine[] = [];
  private nextId = 0;
  private historyCount = 0;
  private liveCount = 0;
  private trimmed = false;

  constructor(
    readonly capacity: number,
    readonly maxLineLength = 4000,
  ) {
    if (capacity < 1) throw new Error('capacity must be >= 1');
  }

  get size(): number {
    return this.lines.length;
  }

  /** Lines of the file already received (tail, live and history): the next history `skip`. */
  get fromEnd(): number {
    return this.historyCount + this.liveCount;
  }

  get historyAvailable(): boolean {
    return !this.trimmed && this.lines.length < this.capacity;
  }

  append(texts: readonly string[]) {
    for (const text of texts) {
      this.lines.push({ id: this.nextId++, text: this.cut(text) });
    }
    this.liveCount += texts.length;
    const overflow = this.lines.length - this.capacity;
    if (overflow > 0) {
      // drop a block (10% of capacity) at once to avoid shifting the array for every line
      const drop = Math.min(this.lines.length, overflow + Math.floor(this.capacity / 10));
      this.lines.splice(0, drop);
      this.trimmed = true;
    }
  }

  /** Older lines (in file order). @returns how many were actually added */
  prepend(texts: readonly string[]): number {
    const room = this.capacity - this.lines.length;
    if (room <= 0 || texts.length === 0) return 0;
    const kept = texts.slice(Math.max(0, texts.length - room));
    const older = kept.map((text) => ({ id: this.nextId++, text: this.cut(text) }));
    this.lines = older.concat(this.lines);
    this.historyCount += texts.length;
    return kept.length;
  }

  clear() {
    this.lines = [];
    this.historyCount = 0;
    this.liveCount = 0;
    this.trimmed = false;
  }

  snapshot(): LogLine[] {
    return this.lines.slice();
  }

  private cut(text: string): string {
    return text.length <= this.maxLineLength ? text : text.slice(0, this.maxLineLength) + ' … [troncato]';
  }
}
