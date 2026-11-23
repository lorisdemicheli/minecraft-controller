import { TestBed } from '@angular/core/testing';
import { Theme } from './theme';

describe('Theme', () => {
  beforeEach(() => {
    localStorage.removeItem('theme');
    document.documentElement.removeAttribute('data-theme');
  });

  it('toggles, applies and remembers the choice', () => {
    const theme = TestBed.inject(Theme);
    theme.set('light');
    theme.toggle();
    expect(theme.mode()).toBe('dark');
    expect(theme.isDark()).toBe(true);
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  it('starts from the saved choice', () => {
    localStorage.setItem('theme', 'dark');
    expect(TestBed.inject(Theme).mode()).toBe('dark');
  });
});
