import { SseParser } from './sse-parser';

describe('SseParser', () => {
  it('parses LF separated events', () => {
    const p = new SseParser();
    expect(p.push('data: one\n\ndata: two\n\n').map((m) => m.data)).toEqual(['one', 'two']);
  });

  it('parses CRLF and lone CR line endings', () => {
    const p = new SseParser();
    expect(p.push('data: a\r\n\r\ndata: b\r\rdata: c\n\n').map((m) => m.data)).toEqual(['a', 'b', 'c']);
  });

  it('handles a CRLF split across chunks', () => {
    const p = new SseParser();
    // "data: x\r\n\r\n" in three pieces: the second CR already ends the blank line
    expect(p.push('data: x\r')).toEqual([]);
    expect(p.push('\n\r').map((m) => m.data)).toEqual(['x']);
    expect(p.push('\n')).toEqual([]); // the LF of that CRLF is not another blank line
    expect(p.push('data: y\n\n').map((m) => m.data)).toEqual(['y']);
  });

  it('joins multi-line data with newlines', () => {
    const p = new SseParser();
    expect(p.push('data: first\ndata: second\ndata:third\n\n')[0].data).toBe('first\nsecond\nthird');
  });

  it('keeps partial buffers until the event is complete', () => {
    const p = new SseParser();
    expect(p.push('da')).toEqual([]);
    expect(p.push('ta: {"a":')).toEqual([]);
    expect(p.push('1}\n')).toEqual([]);
    expect(p.push('\n')[0].data).toBe('{"a":1}');
  });

  it('ignores comments (heartbeats) and unknown fields', () => {
    const p = new SseParser();
    expect(p.push(':keepalive\n\nfoo: bar\n\n')).toEqual([]);
    expect(p.push(': ping\ndata: real\n\n').map((m) => m.data)).toEqual(['real']);
  });

  it('reads event, id and retry', () => {
    const p = new SseParser();
    const [m] = p.push('event: alpha\nid: 7\nretry: 3000\ndata: x\n\n');
    expect(m).toEqual({ event: 'alpha', data: 'x', id: '7', retry: 3000 });
    expect(p.lastId).toBe('7');
    expect(p.push('data: y\n\n')[0].event).toBe('message');
  });

  it('removes only one leading space and keeps the rest of the value', () => {
    const p = new SseParser();
    expect(p.push('data:   indented [INFO]  \n\n')[0].data).toBe('  indented [INFO]  ');
    expect(p.push('data\n\n')[0].data).toBe('');
  });

  it('drops an unfinished event when the stream ends', () => {
    const p = new SseParser();
    expect(p.push('data: never dispatched')).toEqual([]);
  });
});
