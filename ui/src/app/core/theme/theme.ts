import { DOCUMENT } from '@angular/common';
import { Injectable, PLATFORM_ID, computed, inject, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'theme';

/**
 * Light / dark theme. The choice is saved in localStorage and applied as `data-theme` on <html>;
 * until the user chooses, the OS preference decides (the CSS media query handles that case).
 */
@Injectable({ providedIn: 'root' })
export class Theme {
  private document = inject(DOCUMENT);
  private browser = isPlatformBrowser(inject(PLATFORM_ID));

  private _mode = signal<ThemeMode>(this.initial());

  readonly mode = this._mode.asReadonly();
  readonly isDark = computed(() => this._mode() === 'dark');

  toggle() {
    this.set(this.isDark() ? 'light' : 'dark');
  }

  set(mode: ThemeMode) {
    this._mode.set(mode);
    this.document.documentElement.setAttribute('data-theme', mode);
    if (!this.browser) return;
    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // storage disabled: the choice lasts until the page is closed
    }
  }

  private initial(): ThemeMode {
    if (!this.browser) return 'light';
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'light' || saved === 'dark') return saved;
    } catch {
      // fall through to the OS preference
    }
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
}
