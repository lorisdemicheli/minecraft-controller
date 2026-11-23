import { formatBytes, formatCpu, formatMemory, percent } from './format';

describe('format', () => {
  it('formats memory, cpu and bytes', () => {
    expect(formatMemory(512)).toBe('512 MB');
    expect(formatMemory(1536)).toBe('1,5 GB');
    expect(formatMemory(undefined)).toBe('—');
    expect(formatCpu(400)).toBe('0,4 vCPU');
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1536)).toBe('1,5 KB');
    expect(formatBytes(null)).toBe('—');
  });

  it('computes a clamped percentage', () => {
    expect(percent(50, 200)).toBe(25);
    expect(percent(300, 200)).toBe(100);
    expect(percent(5, 0)).toBeNull();
    expect(percent(undefined, 10)).toBeNull();
  });
});
