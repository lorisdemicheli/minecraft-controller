/** One dispatched Server-Sent Event. */
export interface SseMessage {
  event: string;
  data: string;
  id?: string;
  retry?: number;
}

/**
 * Incremental parser of a `text/event-stream` body, following the WHATWG spec:
 * - lines end with LF, CRLF or CR (also when CR and LF arrive in different chunks);
 * - `data:` lines of one event are joined with "\n" (multi-line messages);
 * - one leading space after the colon is removed, the rest of the value is kept as is;
 * - lines starting with ":" are comments (heartbeats) and are ignored;
 * - an event is dispatched on a blank line; an unfinished event at the end of the stream is dropped.
 */
export class SseParser {
  private buffer = '';
  private pendingCR = false;
  private data: string[] = [];
  private eventType = '';
  private lastEventId: string | undefined;
  private retry: number | undefined;

  /** Feeds a decoded chunk; returns the events completed by it. */
  push(chunk: string): SseMessage[] {
    const messages: SseMessage[] = [];
    let text = chunk;
    if (this.pendingCR) {
      this.pendingCR = false;
      if (text.startsWith('\n')) {
        text = text.slice(1); // the LF of a CRLF split across chunks
      }
    }
    this.buffer += text;

    let start = 0;
    for (let i = 0; i < this.buffer.length; i++) {
      const c = this.buffer[i];
      if (c !== '\n' && c !== '\r') continue;
      const line = this.buffer.slice(start, i);
      if (c === '\r') {
        if (i + 1 < this.buffer.length) {
          if (this.buffer[i + 1] === '\n') i++;
        } else {
          this.pendingCR = true;
        }
      }
      start = i + 1;
      const message = this.line(line);
      if (message) messages.push(message);
    }
    this.buffer = this.buffer.slice(start);
    return messages;
  }

  /** Last `id:` seen, to send as Last-Event-ID when reconnecting. */
  get lastId(): string | undefined {
    return this.lastEventId;
  }

  private line(line: string): SseMessage | null {
    if (line === '') return this.dispatch();
    if (line.startsWith(':')) return null; // comment / heartbeat

    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    switch (field) {
      case 'data':
        this.data.push(value);
        break;
      case 'event':
        this.eventType = value;
        break;
      case 'id':
        if (!value.includes('\0')) this.lastEventId = value;
        break;
      case 'retry':
        if (/^\d+$/.test(value)) this.retry = Number(value);
        break;
      default: // unknown fields are ignored
    }
    return null;
  }

  private dispatch(): SseMessage | null {
    if (this.data.length === 0) {
      this.eventType = '';
      return null;
    }
    const message: SseMessage = {
      event: this.eventType || 'message',
      data: this.data.join('\n'),
      id: this.lastEventId,
      retry: this.retry,
    };
    this.data = [];
    this.eventType = '';
    return message;
  }
}
